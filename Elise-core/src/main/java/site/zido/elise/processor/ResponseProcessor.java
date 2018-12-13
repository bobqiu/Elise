package site.zido.elise.processor;

import site.zido.elise.http.Response;
import site.zido.elise.select.SelectFactory;
import site.zido.elise.task.Task;

import java.util.Set;

/**
 * the page processor
 *
 * @author zido
 */
public interface ResponseProcessor {
    /**
     * process the response, extract urls to fetch, extract the data and store.
     *
     * @param task     task.
     * @param response response.
     * @return results set
     */
    Set<String> process(Task task, Response response);

    void registerSelector(String token, SelectFactory factory);
}
