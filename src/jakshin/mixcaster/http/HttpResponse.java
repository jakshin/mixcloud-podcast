/*
 * Copyright (C) 2016 Jason Jackson
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package jakshin.mixcaster.http;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.util.LinkedList;
import java.util.Locale;
import static jakshin.mixcaster.logging.Logging.*;

/**
 * Provides a response to a single incoming HTTP request, first parsing headers to understand the request,
 * and delegating to an appropriate HttpResponder instance.
 */
class HttpResponse implements Runnable {
    /**
     * Creates a new instance of the class.
     * @param socket The HTTP socket.
     */
    HttpResponse(Socket socket) {
        this.socket = socket;
    }

    /**
     * Parses the incoming HTTP request, and provides a response.
     */
    @Override
    public void run() {
        // we manually close readers/writers/streams because the socket gets closed when any of them are closed,
        // so we need to control their life-cycle carefully
        BufferedReader reader = null;
        BufferedWriter writer = null;
        BufferedOutputStream out = null;
        HttpRequest request = null;

        try {
            // initialize
            reader = new BufferedReader(new InputStreamReader(this.socket.getInputStream(), "ISO-8859-1"));
            writer = new BufferedWriter(new OutputStreamWriter(this.socket.getOutputStream(), "UTF-8"), 100_000);
            out = new BufferedOutputStream(this.socket.getOutputStream(), 100_000);

            // parse and check the request
            request = this.parseRequestHeaders(reader);

            if (request.httpVersion == null || !request.httpVersion.contains("HTTP/1.")) {
                throw new HttpException(505, String.format("HTTP Version %s not supported", request.httpVersion));
            }
            else if (request.method == null || (!request.method.equals("GET") && !request.method.equals("HEAD"))) {
                throw new HttpException(405, String.format("Method %s Not Allowed", request.method));
            }
            else if (request.url == null || request.url.isEmpty()) {
                throw new HttpException(400, "Bad Request: empty URL");
            }

            // note that we don't handle the Expect header, nor do we handle "100-continue";
            // technically this violates the HTTP/1.1 spec, but doesn't seem to matter for our purposes
            String expect = request.headers.get("Expect");
            if (expect != null && !expect.isEmpty()) {
                logger.log(WARNING, "Expect header received but not handled: {0}", expect);
            }

            // we don't handle the If-Range header, either
            String ifRange = request.headers.get("If-Range");
            if (ifRange != null && !ifRange.isEmpty()) {
                logger.log(WARNING, "If-Range header received but not handled: {0}", ifRange);
            }

            // route the request to a responder
            String normalizedPathStr = request.path.toLowerCase(Locale.ROOT);

            if (normalizedPathStr.equals("/")) {
                // request at the root of the site; serve a banner page
                new BannerResponder().respond(request, writer);
            }
            else if (normalizedPathStr.endsWith("/podcast.xml")) {
                // RSS XML request
                new PodcastXmlResponder().respond(request, writer);
            }
            else if (normalizedPathStr.endsWith("/favicon.ico")) {
                // favicon request
                new FavIconResponder().respond(request, writer, out);
            }
            else if (normalizedPathStr.endsWith("/")) {
                // folder request
                new FolderResponder().respond(request, writer);
            }
            else {
                // any other request must be for a file (or a folder, with no terminating slash in the URL)
                new FileResponder().respond(request, writer, out);
            }
        }
        catch (Throwable ex) {
            HttpException httpException = (ex instanceof HttpException) ? (HttpException) ex : null;
            boolean sendResponseHeaders = true;

            if (httpException != null && httpException.httpResponseCode < 500) {
                // log non-5xx HTTP exceptions as INFO
                String msg = String.format("HTTP error: %d %s", httpException.httpResponseCode, ex.getMessage());
                logger.log(INFO, msg);
            }
            else if (request != null && request.isFromITunes() &&
                    ex instanceof SocketException && "Broken pipe".equalsIgnoreCase(ex.getMessage())) {
                // while streaming a podcast episode without downloading it, iTunes isses range and non-range requests,
                // and regularly closes the socket before it's received all bytes requested;
                // this appears to be normal behavior, so log it as such (HTTP headers have already been sent)
                logger.log(INFO, "iTunes closed the connection early");
                sendResponseHeaders = false;
            }
            else {
                logger.log(ERROR, ex.getMessage(), ex);
            }

            // send HTTP response headers, if appropriate
            try {
                if (sendResponseHeaders) {
                    boolean isHeadRequest = (request == null) ? false : request.isHead();
                    HttpHeaderWriter headerWriter = new HttpHeaderWriter();
                    headerWriter.sendErrorHeadersAndBody(writer, ex, isHeadRequest);
                }
            }
            catch (Throwable ex2) {
                // we're pretty bad off if we can't even send the response headers; log and give up
                logger.log(ERROR, "Failed to send HTTP error response headers", ex2);
            }
        }
        finally {
            this.closeAThing(reader, "the socket's reader");
            this.closeAThing(writer, "the socket's writer");
            this.closeAThing(out, "the socket's output stream");
        }
    }

    /**
     * Parses the incoming HTTP request headers into an HttpRequest object.
     *
     * @param reader The reader from which request headers should be read.
     * @return The HTTP request.
     * @throws HttpException
     * @throws IOException
     */
    private HttpRequest parseRequestHeaders(BufferedReader reader) throws HttpException, IOException {
        HttpRequest request = null;
        String lastHeaderName = null;

        StringBuilder loggedHeaders = new StringBuilder(500);
        LinkedList<String> unparsableHeaders = new LinkedList<>();

        while (true) {
            String line = reader.readLine();
            if (line == null || line.isEmpty()) break;

            loggedHeaders.append(String.format("%n    -> %s", line));

            if (request == null) {
                // first line
                String[] parts = line.split("\\s+");
                if (parts.length != 3) {
                    throw new HttpException(400, "Bad Request: " + line);
                }

                request = new HttpRequest(parts[0], parts[1], parts[2]);
            }
            else if (lastHeaderName != null && Character.isWhitespace(line.charAt(0))) {
                // continuation line
                String oldValue = request.headers.get(lastHeaderName);
                request.headers.put(lastHeaderName, oldValue + line.trim());
            }
            else {
                // header line (Name: Value)
                int colon = line.indexOf(':');

                if (colon > 0) {
                    String headerName = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    request.headers.put(headerName, value);
                    lastHeaderName = headerName;
                }
                else {
                    unparsableHeaders.add(line);
                }
            }
        }

        logger.log(DEBUG, "Received HTTP request headers{0}", loggedHeaders);
        for (String header : unparsableHeaders) {
            logger.log(WARNING, "Unparsable HTTP request header: {0}", header);
        }

        return request;
    }

    /**
     * Convenience method which closes something which can be closed.
     *
     * @param thing The thing to be closed.
     * @param description A description of the thing which will be closed.
     */
    private void closeAThing(Closeable thing, String description) {
        if (thing == null) return;

        try {
            thing.close();
        }
        catch (IOException ex) {
            // responders are expected to flush at the end of finishResponse(), making this unlikely
            String msg = String.format("Failed to close %s", description);
            logger.log(WARNING, msg, ex);
        }
    }

    /** The socket on which the HTTP request was received. */
    private final Socket socket;
}
