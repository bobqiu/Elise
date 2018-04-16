package com.hnqc.ironhand.spider.distributed;

import com.hnqc.ironhand.spider.*;
import com.hnqc.ironhand.spider.distributed.configurable.DefRootExtractor;
import com.hnqc.ironhand.spider.distributed.configurable.PageModelExtractor;
import com.hnqc.ironhand.spider.distributed.downloader.ThreadAsyncDownloader;
import com.hnqc.ironhand.spider.distributed.pipeline.MappedPageModelPipeline;
import com.hnqc.ironhand.spider.distributed.pipeline.ModelPipeline;
import com.hnqc.ironhand.spider.distributed.pipeline.PageModelCollectorPipeline;
import com.hnqc.ironhand.spider.distributed.pipeline.PageModelPipeline;
import com.hnqc.ironhand.spider.distributed.processor.MappedModelPageProcessor;
import com.hnqc.ironhand.spider.pipeline.CollectorPipeline;
import com.hnqc.ironhand.spider.processor.PageProcessor;
import com.hnqc.ironhand.spider.scheduler.QueueScheduler;
import com.hnqc.ironhand.spider.scheduler.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DsSpider
 *
 * @author zido
 * @date 2018/04/16
 */
public class DsSpider extends Spider implements IDsSpider {

    private final static Logger logger = LoggerFactory.getLogger(DsSpider.class);

    private List<DefRootExtractor> defRootExtractors = new ArrayList<>();

    public DsSpider(Task task, PageModelPipeline pageModelPipeline, AbstractAsyncDownloader downloader, DefRootExtractor... defRootExtractor) {
        this(task.getSite(), pageModelPipeline, downloader, defRootExtractor);
        super.setId(task.getId());
    }

    protected DsSpider(MappedModelPageProcessor modelPageProcessor, AbstractAsyncDownloader downloader) {
        super(modelPageProcessor);
        this.setDownloader(downloader);
    }

    public DsSpider(Site site, PageModelPipeline pageModelPipeline, AbstractAsyncDownloader downloader, DefRootExtractor... defRootExtractor) {
        this(new MappedModelPageProcessor(site,
                Arrays.stream(defRootExtractor).map(PageModelExtractor::new).collect(Collectors.toList())
        ), downloader);
        ModelPipeline modelPipeline = new ModelPipeline();
        for (DefRootExtractor def : defRootExtractor) {
            if (pageModelPipeline != null) {
                modelPipeline.putPageModelPipeline(def.getName(), pageModelPipeline);
            }
            defRootExtractors.add(def);
        }
        super.addPipeline(modelPipeline);
    }

    @Override
    protected CollectorPipeline getCollectorPipeline() {
        return new PageModelCollectorPipeline<>(defRootExtractors.get(0), new MappedPageModelPipeline());
    }

    @Override
    public void run() {
        Scheduler scheduler = getScheduler();
        Request request = scheduler.poll(this);
        processRequest(request);
    }

    @Override
    public void run(Request request, Page page) {
        if (page.isDownloadSuccess()) {
            onDownloadSuccess(request, page);
        } else {
            onDownloaderFail(request);
        }
    }

    public static Holder prepare(Task task, PageModelPipeline pageModelPipeline, AbstractAsyncDownloader downloader) {
        return new Holder(task, pageModelPipeline).setDownloader(downloader);
    }

    public static Holder prepare(Task task, PageModelPipeline pageModelPipeline) {
        return new Holder(task, pageModelPipeline);
    }

    public static class Holder {
        private Task task;
        private PageModelPipeline pageModelPipeline;
        private AbstractAsyncDownloader downloader = new ThreadAsyncDownloader();
        private String[] initialUrl;
        private Scheduler scheduler = new QueueScheduler();

        private Holder(Task task, PageModelPipeline pageModelPipeline) {
            this.task = task;
            this.pageModelPipeline = pageModelPipeline;
        }

        public Holder setDownloader(AbstractAsyncDownloader downloader) {
            this.downloader = downloader;
            return this;
        }

        public Holder addUrl(String... initialUrl) {
            this.initialUrl = initialUrl;
            return this;
        }

        public Holder setScheduler(Scheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public void run(DefRootExtractor... defRootExtractor) {
            DsSpider spider = new DsSpider(task, pageModelPipeline, downloader, defRootExtractor);
            spider.setScheduler(scheduler);
            spider.addUrl(initialUrl).run();
        }

        public void run(Request request, Page page, DefRootExtractor... defRootExtractors) {
            DsSpider spider = new DsSpider(task, pageModelPipeline, downloader, defRootExtractors);
            spider.setScheduler(scheduler);
            spider.run(request, page);
        }
    }

    @Override
    protected void processRequest(Request request) {
        getDownloader().download(request, this);
    }
}
