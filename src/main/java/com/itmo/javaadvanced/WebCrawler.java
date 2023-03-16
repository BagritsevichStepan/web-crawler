package com.itmo.javaadvanced;

import info.kgeorgiy.java.advanced.crawler.*;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public class WebCrawler implements Crawler {
    private static final int DEFAULT_DOWLOADERS = 16;
    private static final int DEFAULT_EXTRACTORS = 16;
    private static final int DEFAULT_PER_HOST = 8;
    public static final int DEFAULT_DEPTH = 2;
    public static final int[] DEFAULT_VALUES = new int[]{DEFAULT_DOWLOADERS, DEFAULT_EXTRACTORS, DEFAULT_PER_HOST};

    private final Downloader downloader;
    private final int downloaders;
    private final int extractors;
    private final int perHost;
    private final BFSWebCrawler bfsWebCrawler;
    private ExecutorService downloadersPool;
    private ExecutorService extractorsPool;
    private Map<String, HostExecutor> hostExecutors;
    private Map<String, IOException> errors;
    private Set<String> downloadedDocuments;

    public WebCrawler(Downloader downloader, int downloaders, int extractors, int perHost) {
        this.downloader = downloader;
        this.downloaders = downloaders;
        this.extractors = extractors;
        this.perHost = perHost;
        bfsWebCrawler = new BFSWebCrawler();
        initialize();
    }

    private void initialize() {
        downloadersPool = Executors.newFixedThreadPool(downloaders);
        extractorsPool = Executors.newFixedThreadPool(extractors);
        hostExecutors = new ConcurrentHashMap<>();
        errors = new ConcurrentHashMap<>();
        downloadedDocuments = ConcurrentHashMap.newKeySet();
    }

    @Override
    public Result download(String s, int i) {
        return downloadDocuments(s, i);
    }

    private Result downloadDocuments(String startUrl, int maxDepth) {
        bfsWebCrawler.bfs(startUrl, maxDepth);
        return new Result(new ArrayList<>(downloadedDocuments), errors);
    }

    @Override
    public void close() {
        downloadersPool.shutdown();
        extractorsPool.shutdown();
    }

    public static void main(String[] args) {
        if (args.length == 0 || Arrays.stream(args).anyMatch(Objects::isNull)) {
            System.err.println(
                    args.length == 0
                            ? "Must be at least 1 argument."
                            : "No argument cannot be null."
            );
            return;
        }

        int[] values = DEFAULT_VALUES;
        int depth = DEFAULT_DEPTH;
        if (args.length > 1) {
            try {
                depth = Integer.parseInt(args[1]);
                for (int i = 2; i < args.length; i++) {
                    values[i] = Integer.parseInt(args[i]);
                }
            } catch (NumberFormatException e) {
                System.err.println("Arguments 1 - 4 must be integers.");
                return;
            }
        }

        try {
            WebCrawler webCrawler = new WebCrawler(new CachingDownloader(), values[0], values[1], values[2]);
            webCrawler.download(args[0], depth);
        } catch (IOException e) {
            System.err.println("An error occurred: " + e.getMessage());
        }
    }

    private class BFSWebCrawler {
        private BlockingQueue<String> queue;
        private Set<String> seenUrls;
        private AtomicInteger urlCountInNextLayer;
        private Phaser phaser;
        private int countUrlInCurrentLayer;

        private void initializeBfs() {
            initialize();
            queue = new LinkedBlockingQueue<>();
            seenUrls = ConcurrentHashMap.newKeySet();
            urlCountInNextLayer = new AtomicInteger();
        }

        private void bfs(String startUrl, int maxDepth) {
            initializeBfs();
            phaser = new Phaser(1);
            addNextDepthUrl(startUrl);
            updateDepth();

            while (!queue.isEmpty()) {
                String url = queue.poll();
                countUrlInCurrentLayer--;

                try {
                    phaser.register();
                    HostExecutor hostExecutor = getUrlHostExecutor(url);
                    hostExecutor.add(() -> {
                        try {
                            Document document = downloader.download(url);
                            downloadedDocuments.add(url);
                            if (phaser.getPhase() + 1 < maxDepth) {
                                phaser.register();
                                extractorsPool.submit(() -> {
                                    try {
                                        document.extractLinks().stream()
                                                .filter(Predicate.not(this::urlWasSeen))
                                                .forEach(this::addNextDepthUrl);
                                    } catch (IOException e) {
                                        saveError(url, e);
                                    } finally {
                                        phaser.arriveAndDeregister();
                                    }
                                });
                            }
                        } catch (IOException e) {
                            saveError(url, e);
                        } finally {
                            phaser.arriveAndDeregister();
                            hostExecutor.urlWasDownloaded();
                            hostExecutor.tryToDownloadNewUrl();
                        }
                    });
                } catch (MalformedURLException e) {
                    saveError(url, e);
                }

                if (countUrlInCurrentLayer == 0) {
                    phaser.arriveAndAwaitAdvance();
                    updateDepth();
                }
            }
        }

        private void addNextDepthUrl(String url) {
            queue.add(url);
            seenUrls.add(url);
            urlCountInNextLayer.incrementAndGet();
        }

        private void updateDepth() {
            countUrlInCurrentLayer = urlCountInNextLayer.getAndSet(0);
        }

        private boolean urlWasSeen(String url) {
            return seenUrls.contains(url);
        }
    }

    private class HostExecutor {
        private final Queue<Runnable> urlsToDownload;
        private int countDownloadingUrls;

        HostExecutor() {
            urlsToDownload = new LinkedBlockingQueue<>();
            countDownloadingUrls = 0;
        }

        private synchronized void add(Runnable urlToDownload) {
            if (countDownloadingUrls < perHost) {
                downloadersPool.submit(urlToDownload);
                countDownloadingUrls++;
            } else {
                urlsToDownload.add(urlToDownload);
            }
        }

        private synchronized void urlWasDownloaded() {
            countDownloadingUrls--;
        }

        private synchronized void tryToDownloadNewUrl() {
            if (!urlsToDownload.isEmpty() && countDownloadingUrls < perHost) {
                downloadersPool.submit(urlsToDownload.poll());
                countDownloadingUrls++;
            }
        }
    }

    private void saveError(String url, IOException e) {
        errors.put(url, e);
    }

    private HostExecutor getUrlHostExecutor(String url) throws MalformedURLException {
        final String host = getUrlHost(url);
        hostExecutors.computeIfAbsent(host, absent -> new HostExecutor());
        return hostExecutors.get(host);
    }

    private String getUrlHost(String url) throws MalformedURLException {
        return URLUtils.getHost(url);
    }
}
