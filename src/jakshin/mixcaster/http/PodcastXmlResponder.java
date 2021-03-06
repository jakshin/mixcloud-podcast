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

import jakshin.mixcaster.ApplicationException;
import jakshin.mixcaster.download.DownloadQueue;
import jakshin.mixcaster.mixcloud.MixcloudFeed;
import jakshin.mixcaster.mixcloud.MixcloudScraper;
import jakshin.mixcaster.podcast.Podcast;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Writer;
import java.util.Date;
import static jakshin.mixcaster.logging.Logging.*;

/**
 * Responds to an HTTP request for RSS XML.
 */
public class PodcastXmlResponder {
    /**
     * Responds to the RSS XML request.
     *
     * @param request The incoming HTTP request.
     * @param writer A writer which can be used to output the response.
     * @throws ApplicationException
     * @throws HttpException
     * @throws IOException
     */
    void respond(HttpRequest request, Writer writer) throws ApplicationException, HttpException, IOException {
        String feedName = this.getSecondToLastComponentOfPath(request.path);

        if (feedName == null || feedName.isEmpty()) {
            // 404 would also be fine, but we use 403 instead, to distinguish between an unexpected local podcast.xml URL,
            // and a local URL which looks okay but which doesn't map to a valid Mixcloud feed (handled below)
            throw new HttpException(403, "Forbidden");
        }

        logger.log(INFO, "Serving RSS XML for feed: {0}", feedName);

        // get a feed, either from cache or by scraping
        FeedCache cache = FeedCache.getInstance();
        MixcloudFeed feed = cache.getFromCache(feedName);

        if (feed == null) {
            try {
                // kick off a scraper
                String mixcloudFeedUrl = String.format("https://www.mixcloud.com/%s/", feedName);
                MixcloudScraper scraper = new MixcloudScraper();
                feed = scraper.scrape(mixcloudFeedUrl);

                // cache the MixcloudFeed instance
                cache.addToCache(feedName, feed);
            }
            catch (FileNotFoundException ex) {
                // Mixcloud returned 404 for the feed URL; pass it along
                throw new HttpException(404, "Not Found", ex);
            }
        }
        else {
            logger.log(INFO, "Feed retrieved from cache: {0}", feedName);
        }

        // handle If-Modified-Since
        HttpHeaderWriter headerWriter = new HttpHeaderWriter();
        Date scraped = new Date(feed.scraped.getTime() / 1000 * 1000);  // truncate milliseconds for comparison

        if (headerWriter.sendNotModifiedHeadersIfNeeded(request, writer, scraped)) {
            return;  // the request was satisfied via not-modified response headers
        }

        // build the RSS XML
        Podcast podcast = feed.createPodcast(request.host());
        String rssXml = podcast.createXml();

        // kick off any downloads from Mixcloud which are now needed
        DownloadQueue downloads = DownloadQueue.getInstance();
        int downloadCount = downloads.queueSize();

        if (downloadCount == 0) {
            String msg = feed.tracks.isEmpty() ? "No tracks to download" : "All tracks have already been downloaded";
            logger.log(INFO, msg);
        }
        else {
            String tracksStr = (downloadCount == 1) ? "track" : "tracks";
            logger.log(INFO, String.format("Starting download of %d %s", downloadCount, tracksStr));
            downloads.processQueue();
        }

        // send the response headers
        headerWriter.sendSuccessHeaders(writer, scraped, "application/xml", rssXml.length());

        // send the RSS XML, if needed; note that we always send the whole thing,
        // as we don't expect to receive a Range header for this type of request
        if (!request.isHead()) {
            writer.write(rssXml);
            writer.flush();
        }
    }

    /**
     * Gets the second-to-last component of a path. For example: /foo/bar/bar/ => bar.
     *
     * @param pathStr The path.
     * @return The path's second-to-last component, or null if it doesn't have one.
     */
    private String getSecondToLastComponentOfPath(String pathStr) {
        String[] components = pathStr.split("/");  // trailing empty string not included
        if (components.length < 2) return null;
        return components[components.length - 2];
    }
}
