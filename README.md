# web-crawler
The web crawler uses parallel BFS to visit urls and downloads them using java-multithreading.

Technologies that were used: __ExecutorService__, __Phaser__ and other classes from __java.util.concurrent__

## Problem statement
Write a thread-safe WebCrawler class that recursively visits web sites:
1. The WebCrawler class must have a constructor
  
  `public WebCrawler(Downloader downloader, int downloaders, int extractors, int perHost)`
   + `downloader` allows you to download pages and extract links from them
   + `downloaders` - the maximum number of pages to be downloaded at the same time
   + `extractors` - the maximum number of pages from which links are extracted at the same time
   + `perHost` — maximum number of pages downloaded at the same time from one host. To determine the host, use the `getHost` method of `URLUtils` class
   from tests.
   
2. The Class `WebCrawler` must implement `Crawler` interface:
```
public interface Crawler extends AutoCloseable {
    Result download(String url, int depth);

    void close();
}
```
   + The `download` method should visit pages in BFS order starting from a specified URL to a specified depth and return a list of downloaded pages and
   files. For example, if the depth is 1, only the specified page should be downloaded. If the depth is 2, then the specified page and those pages and
   files to which it has a link, and so on. This method can be called parallel in several threads
   + Downloading and processing pages (extracting links) should be as parallel as possible, taking into account the limitations on the number of
   simultaneously loaded pages (including from a single host) and pages from which links are loaded
   + For multithreading, it is allowed to create up to `downloaders + extractors` threads
   + The `close` method must terminate all threads
   
3. `Downloader` passed as the first argument of the constructor must be used to download pages:
```
public interface Downloader {
    public Document download(final String url) throws IOException;
}
```
The `download` method downloads a document by its address (URL).

4. `Document` allows you to retrieve links on the downloaded page:
```
public interface Document {
    List<String> extractLinks() throws IOException;
}
```
The links returned by the `Document` are absolute and have the http or https scheme.

5. The main method must be implemented to start BFS algorithm from the command line. Command line:
```
WebCrawler url [depth [downloads [extractors [perHost]]]]
```
You need to use the `CachingDownloader` from the tests to download the pages.




