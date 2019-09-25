package com.liferay.metro.portlet.command.render;

import com.liferay.journal.model.JournalArticle;
import com.liferay.metro.constants.AutocompletePortletKeys;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.json.JSONArray;
import com.liferay.portal.kernel.json.JSONFactoryUtil;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.Company;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.portlet.bridges.mvc.MVCResourceCommand;
import com.liferay.portal.kernel.search.*;
import com.liferay.portal.kernel.service.CompanyLocalService;
import com.liferay.portal.kernel.theme.ThemeDisplay;
import com.liferay.portal.kernel.util.ParamUtil;
import com.liferay.portal.kernel.util.PortalUtil;
import com.liferay.portal.kernel.util.WebKeys;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.portlet.ResourceRequest;
import javax.portlet.ResourceResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component(
        immediate = true,
        property = {
                "javax.portlet.name=" + AutocompletePortletKeys.Autocomplete,
                "mvc.command.name=getLiferayContents"
        }
)
public class AutoCompleteMVCResourceCommand implements MVCResourceCommand {

    private final String TEXT_TO_SEARCH = "textToSearch";
    private final String PORTLET_JSON_VALUE = "value";
    private final int QUERY_START = 0;
    private final int QUERY_END = 20;
    private static final Log log = LogFactoryUtil.getLog(AutoCompleteMVCResourceCommand.class);

    @Reference
    CompanyLocalService companyLocalService;

    @Override
    public boolean serveResource(ResourceRequest resourceRequest, ResourceResponse resourceResponse) {
        String textToSearch = ParamUtil.getString(resourceRequest, TEXT_TO_SEARCH);
        // Creation of the indexers
        Indexer<User> indexerUser = IndexerRegistryUtil.nullSafeGetIndexer(User.class);
        Indexer<JournalArticle> indexer = IndexerRegistryUtil.nullSafeGetIndexer(JournalArticle.class);

        //Creation of the search contexts
        SearchContext searchContextUser = createUsersSearchContext(
                textToSearch,
                User.class.getName(),
                QUERY_START,
                QUERY_END
        );

        ThemeDisplay themeDisplay = (ThemeDisplay) resourceRequest.getAttribute(WebKeys.THEME_DISPLAY);
        SearchContext searchContextJournal = createJournalSearchContext(
                textToSearch,
                themeDisplay,
                User.class.getName(),
                QUERY_START,
                QUERY_END
        );

        //Searchs and results
        Document[] usersDocs = getSearchDocuments(indexerUser, searchContextUser);
        Document[] journalDocs = getSearchDocuments(indexer, searchContextJournal);

        // Handle results
        List<String> resultEntries = Stream.concat(
                getResultEntriesFromDocuments(usersDocs, "fullName").stream(),
                getResultEntriesFromDocuments(journalDocs, "title_" + themeDisplay.getLocale().toString()).stream()
        ).collect(Collectors.toList());

        //Send results
        try {
            resourceResponse.getWriter().print(getJSONResultEntries(resultEntries));
        } catch (IOException e) {
            logErrorMessage(e.getMessage());
        }

        return true;
    }

    private List<String> getResultEntriesFromDocuments(Document[] documents, String documentAttributeName) {
        return Arrays.stream(Objects.requireNonNull(documents))
                .filter(Objects::nonNull)
                .map(doc -> doc.get(documentAttributeName))
                .collect(Collectors.toList());
    }

    private SearchContext createJournalSearchContext(String keywords, ThemeDisplay themeDisplay, String className, int start, int end) {
        SearchContext searchContext = createDefaultSearchContent(keywords, className, start, end);
        final List<Long> scopeGroupIds = new ArrayList<>();
        // Global site
        scopeGroupIds.add(getDefaultGroupId());
        // Current site
        scopeGroupIds.add(themeDisplay.getScopeGroupId());
        final long[] groupIds = scopeGroupIds.stream().mapToLong(Long::longValue).toArray();
        searchContext.setGroupIds(groupIds);
        return searchContext;
    }

    private SearchContext createUsersSearchContext(String keywords, String className, int start, int end) {
        return createDefaultSearchContent(keywords, className, start, end);
    }

    private SearchContext createDefaultSearchContent(String keywords, String className, int start, int end) {
        final SearchContext searchContext = new SearchContext();
        final String[] classNames = new String[]{className};
        searchContext.setEntryClassNames(classNames);
        searchContext.setCompanyId(PortalUtil.getDefaultCompanyId());
        searchContext.setStart(start);
        searchContext.setEnd(end);
        searchContext.setKeywords(keywords);
        return searchContext;
    }

    private Document[] getSearchDocuments(Indexer indexer, SearchContext searchContext) {
        Hits hits;
        try {
            BooleanQuery booleanQueryUser = indexer.getFullQuery(searchContext);
            hits = IndexSearcherHelperUtil.search(searchContext, booleanQueryUser);
        } catch (SearchException e) {
            hits = null;
            logErrorMessage(e.getMessage());
        }
        return hits != null ? hits.getDocs() : null;
    }


    private JSONArray getJSONResultEntries(List<String> resultEntries) {
        final JSONArray jsonResultEntries = JSONFactoryUtil.createJSONArray();
        resultEntries.forEach(entry -> {
            JSONObject jsonEntry = JSONFactoryUtil.createJSONObject();
            jsonEntry.put(PORTLET_JSON_VALUE, entry);
            jsonResultEntries.put(jsonEntry);
        });
        return jsonResultEntries;
    }

    private long getDefaultGroupId() {
        long groupId = 0;
        try {
            long companyId = PortalUtil.getDefaultCompanyId();
            Company company = companyLocalService.getCompany(companyId);
            groupId = company.getGroupId();
        } catch (PortalException e) {
            logErrorMessage(e.getMessage());
        }
        return groupId;
    }

    private void logErrorMessage(String message) {
        if (log.isErrorEnabled()) {
            log.error(message);
        }
    }
}
