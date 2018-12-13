package site.zido.elise.processor;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import site.zido.elise.custom.GlobalConfig;
import site.zido.elise.http.Response;
import site.zido.elise.select.*;
import site.zido.elise.select.api.impl.DefaultPartition;
import site.zido.elise.select.api.impl.NotSafeSelectableResponse;
import site.zido.elise.select.configurable.ResponseHandler;
import site.zido.elise.select.configurable.Source;
import site.zido.elise.select.matcher.Matcher;
import site.zido.elise.task.Task;
import site.zido.elise.task.model.Action;
import site.zido.elise.task.model.Model;
import site.zido.elise.task.model.Partition;
import site.zido.elise.utils.EventUtils;
import site.zido.elise.utils.ValidateUtils;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * model page processor with extractor,
 *
 * @author zido
 */
public class DefaultResponseProcessor implements ListenableResponseProcessor {
    private static Logger LOGGER = LoggerFactory.getLogger(DefaultResponseProcessor.class);
    private static final String HTTP_LABEL = "http";
    private Set<ProcessorEventListener> listeners = new HashSet<>();
    private Saver saver;
    private Map<String, Selector> selectors = new HashMap<>();

    /**
     * Instantiates a new Default response handler.
     *
     * @param saver the saver
     */
    public DefaultResponseProcessor(Saver saver) {
        this.saver = saver;
    }

    private List<Object> processSelectors(final Response response,
                                          final ResponseBodyContentHolder holder,
                                          final Action action,
                                          Object partition) throws SelectorMatchException {
        List<Object> partitions = new ArrayList<>();
        Selector selector = selectors.get(action.getToken());
        if (selector != null) {
            switch (action.getSource()) {
                case Source.URL:
                    partitions = selector.selectObj(response.getUrl(), action.getExtras());
                    break;
                case Source.HTML:
                    partitions = selector.selectObj(holder.getDocument(), action.getExtras());
                    break;
                case Source.BODY:
                    partitions = selector.selectObj(response.getBody(), action.getExtras());
                    break;
                case Source.CODE:
                    partitions = selector.selectObj(response.getStatusCode(), action.getExtras());
                    break;
                case Source.TEXT:
                    partitions = selector.selectObj(holder.getHtml(), action.getExtras());
                    break;
                case Source.PARTITION:
                    if (partition == null) {
                        return null;
                    }
                    List<Object> result = selector.selectObj(partition, action.getExtras());
                    if (!ValidateUtils.isEmpty(result)) {
                        partitions.addAll(result);
                    }
                    break;
                default:
            }
            if (ValidateUtils.isEmpty(partitions)) {
                return null;
            }
            List<Action> children = action.getChildren();
            List<Object> selectResults = new LinkedList<>();
            for (Object o : partitions) {
                for (Action child : children) {
                    List<Object> results = processSelectors(response, holder, child, o);
                    if (!ValidateUtils.isEmpty(results)) {
                        selectResults.addAll(results);
                    }
                }
            }
            return selectResults;
        }
        return null;
    }

    @Override
    public Set<String> process(Task task, final Response response) throws SelectorMatchException {
        Model model = task.getModel();
        ResponseBodyContentHolder holder = new ResponseBodyContentHolder(response, task.getConfig());
        //find helpers
        List<Action> helpers = model.getHelpers();
        Set<String> urls = new HashSet<>();
        for (Action helper : helpers) {
            List<Object> results = processSelectors(response, holder, helper, null);
            if (!ValidateUtils.isEmpty(results)) {
                urls.addAll(processLinks(results, response.getUrl()));
            }
        }
        //judge target
        List<Action> targets = model.getTargets();
        boolean isT = false;
        for (Action target : targets) {
            if (!ValidateUtils.isEmpty(processSelectors(response, holder, target, null))) {
                isT = true;
                break;
            }
        }
        if (!isT) {
            return urls;
        }
        // match field
        ResultItem item = new ResultItem();
        Partition partition = model.getPartition();
        if (partition != null) {
            Action action = partition.getAction();
            List<Object> partitions = null;
            if (action != null) {
                partitions = processSelectors(response, holder, action, null);
            }
            if (!ValidateUtils.isEmpty(partitions)) {
                List<Object> fields = new LinkedList<>();
                for (Object part : partitions) {
                    List<Object> field = processSelectors(response, holder, action, part);
                    if (field != null) {
                        fields.add(field);
                    }
                }
            }
        }


        //compile response to selectable response
        NotSafeSelectableResponse selectableResponse = new NotSafeSelectableResponse();
        ResponseHandler handler = task.getHandler();
        handler.onHandle(selectableResponse);
        List<NotSafeSelectableResponse> metas = selectableResponse.getMetas();
        byte[] bytes = response.getBody().getBytes();
        Charset encoding = response.getBody().getEncoding();
        Set<String> links = new HashSet<>();
        String html = null;
        Document document = null;
        String name = selectableResponse.getName();
        //select content from response
        boolean isTarget = false;
        List<ResultItem> resultItems = new ArrayList<>();
        for (NotSafeSelectableResponse meta : metas) {
            Source source = meta.getSource();
            List<LinkSelector> linkSelectors = meta.getLinkSelectors();
            if (html == null) {
                String charset = task.getConfig().get(GlobalConfig.KEY_CHARSET);
                html = new String(bytes, encoding == null ? Charset.forName(charset) : encoding);
            }
            if (document == null) {
                document = Jsoup.parse(html, response.getUrl());
            }
            Set<String> newLinks = extractLinks(linkSelectors, document, response.getUrl());
            links.addAll(newLinks);
            if (!isTarget && meta.getMode() == NotSafeSelectableResponse.MODE_TARGET) {
                // Determine if it is a target link
                List<Matcher> matchers = meta.getMatchers();
                if (!ValidateUtils.isEmpty(matchers)) {
                    //determine from url or code
                    if (source == Source.CODE) {
                        if (matchers
                                .stream()
                                .anyMatch(matcher ->
                                        matcher.matches(response.getStatusCode())
                                )) {
                            isTarget = true;
                        }
                    } else {
                        if (matchers
                                .stream()
                                .anyMatch(matcher ->
                                        matcher.matches(response.getUrl())
                                )) {
                            isTarget = true;
                        }
                    }
                }
            }
        }
        if (!isTarget) {
            return links;
        }

        //save field
        DefaultPartition fieldPair = selectableResponse.getPartition();
        if (fieldPair == null) {
            ResultItem resultItem = processModel(metas, document, name);
            if (resultItem != null) {
                resultItems.add(resultItem);
            }
        } else {
            ElementSelector regionSelector = fieldPair.getRegionSelector();
            List<Node> region = regionSelector.select(document);
            for (Node node : region) {
                ResultItem resultItem = processModel(metas, (Element) node, name);
                if (resultItem != null) {
                    resultItems.add(resultItem);
                }
            }
        }

        if (!ValidateUtils.isEmpty(resultItems)) {
            for (ResultItem resultItem : resultItems) {
                if (resultItem != null) {
                    try {
                        saver.save(resultItem, task);
                        EventUtils.mustNotifyListeners(listeners, listener -> listener.onSaveSuccess(task, resultItem));
                    } catch (Throwable e) {
                        EventUtils.mustNotifyListeners(listeners, listener -> listener.onSaveError(task, resultItem));
                        LOGGER.error("saver have made a exception", e);
                    }
                }
            }
        } else {
            LOGGER.info("response not find anything, response {}", response.getUrl());
        }
        return links;
    }

    private ResultItem processModel(List<NotSafeSelectableResponse> metas, Element document, String modelName) {
        ResultItem item = new ResultItem();
        for (NotSafeSelectableResponse meta : metas) {
            String name = meta.getName();
            Source source = meta.getSource();
            FieldType fieldType = meta.getValueType();
            boolean nullable = meta.getNullable();
            if (meta.getMode() == NotSafeSelectableResponse.MODE_DATA && source == Source.RAW_HTML) {
                ElementSelector fieldSelector = meta.getFieldSelector();
                List<Object> field = processField(document, fieldSelector, fieldType);
                if (!nullable && ValidateUtils.isEmpty(field)) {
                    return null;
                }
                item.put(name, field, fieldType);
            }
        }
        item.setName(modelName);
        return item;
    }

    private List<Object> processField(Node node, ElementSelector fieldElementSelector, FieldType fieldType) {
        List<Node> values = fieldElementSelector.select((Element) node);
        if (!values.isEmpty()) {
            switch (fieldType) {
                case RICH:
                    return values.stream().map(v -> {
                        Fragment fragment = new Fragment();
                        fragment.add(v);
                        return fragment;
                    }).collect(Collectors.toList());
                case TEXT:
                case NUMBER:
                    return values.stream().map(v -> ((Element) v).text()).collect(Collectors.toList());
                case ORIGIN:
                case XML:
                case HTML:
                default:
                    return values.stream().map(Node::toString).collect(Collectors.toList());
            }
        }
        return null;
    }

    private Set<String> processLinks(List<Object> links, String baseUrl) {
        return links.stream().filter(obj -> obj instanceof String).map(obj -> {
            String link = (String) obj;
            link = link.replaceAll("#.*$", "").replace("&amp;", "&");
            if (link.startsWith(HTTP_LABEL)) {
                //已经是绝对路径的，不再处理
                return link;
            }
            try {
                return new URL(new URL(baseUrl), link).toString();
            } catch (MalformedURLException e) {
                LOGGER.error("An error occurred while processing the link,base:[{}],spec:[{}]", baseUrl, link);
                return null;
            }
        }).filter(link -> !ValidateUtils.isEmpty(link)).collect(Collectors.toSet());
    }

    private Set<String> extractLinks(List<LinkSelector> selectors, Document target, String url) {
        if (!ValidateUtils.isEmpty(selectors)) {
            Set<String> helps = new HashSet<>();
            selectors.forEach(linkSelector -> {
                linkSelector.selectAsStr(target).stream().map(link -> {
                    link = link.replaceAll("#.*$", "").replace("&amp;", "&");
                    if (link.startsWith(HTTP_LABEL)) {
                        //已经是绝对路径的，不再处理
                        return link;
                    }
                    try {
                        return new URL(new URL(url), link).toString();
                    } catch (MalformedURLException e) {
                        LOGGER.error("An error occurred while processing the link,base:[{}],spec:[{}]", url, link);
                    }
                    return link;
                }).forEach(helps::add);
            });
            return helps;
        }
        return Collections.EMPTY_SET;
    }

    @Override
    public void addEventListener(ProcessorEventListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeEventListener(ProcessorEventListener listener) {
        listeners.remove(listener);
    }

    public void registorSelector(String token, Selector selector) {
        selectors.put(token, selector);
    }
}
