package site.zido.elise.downloader;

import site.zido.elise.Spider;
import site.zido.elise.common.AutoSwitchDownloader;
import site.zido.elise.common.SimpleRedisDuplicationProcessor;
import site.zido.elise.common.SpringKafkaTaskScheduler;
import site.zido.elise.proxy.Proxy;
import site.zido.elise.proxy.SimpleProxyProvider;
import site.zido.elise.scheduler.NoDepuplicationProcessor;
import site.zido.elise.scheduler.SimpleTaskScheduler;
import site.zido.elise.utils.ValidateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

/**
 * DownloaderClient
 *
 * @author zido
 * @date 2018/04/23
 */
public class DownloaderClient {
    private Spider spider;
    private Logger logger = LoggerFactory.getLogger(DownloaderClient.class);

    private final static String REDIS_URL = "redisUrl";
    private final static String KAFKA_SERVERS = "kafkaServers";

    public DownloaderClient() {
        Properties properties = new Properties();
        try {
            InputStream stream = this.getClass().getResourceAsStream("/config.properties");
            if (stream == null) {
                throw new RuntimeException("config.properties load failed");
            }
            properties.load(stream);
        } catch (IOException e) {
            logger.error("config.properties load failed");
            throw new RuntimeException("加载客户端配置信息失败");
        }
        ProxyReader proxyReader;
        try {
            proxyReader = new ProxyReader(getClass().getResourceAsStream("/proxy.json"));
        } catch (IOException e) {
            proxyReader = new ProxyReader();
        }

        initClient(properties.getProperty(KAFKA_SERVERS),
                properties.getProperty(REDIS_URL),
                proxyReader.getProxies(),
                properties.getProperty("groupId"),
                properties.getProperty("topicAnalyzer"),
                properties.getProperty("topicDownloader"));
    }

    public DownloaderClient(String kafkaServers, String redisUrl, List<Proxy> proxies, String groupId, String topicAnalyzer, String topicDownloader) {
        initClient(kafkaServers, redisUrl, proxies, groupId, topicAnalyzer, topicDownloader);
    }

    private void initClient(String kafkaServers, String redisUrl, List<Proxy> proxies, String groupId, String topicAnalyzer, String topicDownloader) {
        if (ValidateUtils.isEmpty(kafkaServers)) {
            throw new IllegalArgumentException("kafka servers can't be empty");
        }
        logger.info("connect to redis {}", kafkaServers);
        if (ValidateUtils.isEmpty(redisUrl)) {
            throw new IllegalArgumentException("redis url can't be empty");
        }
        logger.info("connect to kafka {}", redisUrl);

        AutoSwitchDownloader downloader = new AutoSwitchDownloader();
        if (!ValidateUtils.isEmpty(proxies)) {
            downloader.setProxyProvider(new SimpleProxyProvider(proxies));
        }

        this.spider = new Spider(
                new SpringKafkaTaskScheduler(
                        new SimpleTaskScheduler(new NoDepuplicationProcessor()).setPoolSize(10),
                        new SimpleRedisDuplicationProcessor(redisUrl))
                        .setTopicAnalyzer(topicAnalyzer)
                        .setTopicDownload(topicDownloader)
                        .setGroupId(groupId)
                        .setBootstrapServers(kafkaServers)
                        .setSavedListener(new OssSavedListener()),
                null,
                downloader
        );
    }

    public void start() {
        logger.info("downloader client start");
        this.spider.start();
    }

    public static void main(String[] args) {
        new DownloaderClient().start();
    }
}