package com.hnqc.ironhand.spider.extractor;

import com.hnqc.ironhand.spider.Page;
import com.hnqc.ironhand.spider.ResultItem;

import java.util.List;

/**
 * model extractor
 *
 * @author zido
 * @date 2018/04/18
 */
public interface ModelExtractor {
    ResultItem extract(Page page);

    List<String> extractLinks(Page page);
}