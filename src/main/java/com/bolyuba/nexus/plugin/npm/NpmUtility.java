package com.bolyuba.nexus.plugin.npm;

import com.bolyuba.nexus.plugin.npm.hosted.NpmHostedRepository;
import com.bolyuba.nexus.plugin.npm.hosted.content.NpmJsonContentLocator;
import com.bolyuba.nexus.plugin.npm.pkg.InvalidPackageRequestException;
import com.bolyuba.nexus.plugin.npm.pkg.PackageCoordinates;
import com.bolyuba.nexus.plugin.npm.pkg.PackageRequest;
import com.bolyuba.nexus.plugin.npm.proxy.content.NpmFilteringContentLocator;
import com.google.gson.Gson;
import com.google.inject.Provider;
import org.sonatype.nexus.proxy.LocalStorageException;
import org.sonatype.nexus.proxy.RequestContext;
import org.sonatype.nexus.proxy.ResourceStoreRequest;
import org.sonatype.nexus.proxy.item.DefaultStorageFileItem;
import org.sonatype.nexus.proxy.item.RepositoryItemUid;
import org.sonatype.nexus.proxy.repository.ProxyRepository;
import org.sonatype.nexus.proxy.storage.UnsupportedStorageOperationException;
import org.sonatype.nexus.proxy.storage.local.LocalRepositoryStorage;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;

/**
 * Utility class that implements most of the commonjs/npm related plumbing.
 *
 * @author Georgy Bolyuba (georgy@bolyuba.com)
 */
@Named
@Singleton
public class NpmUtility {

    static final String NPM_DECORATED_FLAG = "npm.decorated";

    static final String JSON_CONTENT_FILE_NAME = "content.json";

    static final String JSON_MIME_TYPE = "application/json";

    static final String HIDDEN_CACHE_PREFIX = RepositoryItemUid.PATH_SEPARATOR + ".cache";

    final Provider<HttpServletRequest> httpServletRequestProvider;

    @Inject
    public NpmUtility(
            @SuppressWarnings("CdiInjectionPointsInspection") final Provider<HttpServletRequest> httpServletRequestProvider) {
        this.httpServletRequestProvider = httpServletRequestProvider;
    }

    /**
     * Trying to decide if request is coming form npm utility.
     * <p/>
     * Following http://wiki.commonjs.org/wiki/Packages/Registry#HTTP_Request_Method_and_Headers
     * checking Accept for "application/json" would be a good idea. Right now it is not possible as
     * {@link org.sonatype.nexus.web.content.NexusContentServlet#getResourceStoreRequest(javax.servlet.http.HttpServletRequest)}
     * does not map Accept header into anything.
     *
     * @param request request we are about to process
     * @return {@code true} if we think request is coming form npm utility, {@code false} otherwise (for example,
     * if someone is browsing content of the repo in Nexus UI).
     */
    public final boolean isNmpRequest(@SuppressWarnings("UnusedParameters") ResourceStoreRequest request) {

        HttpServletRequest httpServletRequest = httpServletRequestProvider.get();
        if (httpServletRequest == null) {
            return false;
        }

        String accept = httpServletRequest.getHeader("accept");

        return accept != null && accept.toLowerCase().equals(JSON_MIME_TYPE);
    }

    public final String suggestMimeType(@Nonnull String path) {
        // this should take into account if request in from npm or not
        // right now we only know that content.json is json
        if (path.toLowerCase().endsWith(JSON_CONTENT_FILE_NAME)) {
            return JSON_MIME_TYPE;
        }
        return null;
    }

    public final boolean isJson(DefaultStorageFileItem item) {
        return JSON_MIME_TYPE.equals(item.getMimeType());
    }

    public final DefaultStorageFileItem wrapJsonItem(ProxyRepository repository, ResourceStoreRequest request, DefaultStorageFileItem item) {
        NpmFilteringContentLocator decoratedContentLocator = decorateContentLocator(item, request, repository.getRemoteUrl());
        ResourceStoreRequest decoratedRequest = decorateRequest(request);

        DefaultStorageFileItem storageFileItem = new DefaultStorageFileItem(
                repository,
                decoratedRequest,
                item.isReadable(),
                item.isWritable(),
                decoratedContentLocator);

        storageFileItem.getItemContext().put(NPM_DECORATED_FLAG, true);
        return storageFileItem;
    }

    private NpmFilteringContentLocator decorateContentLocator(DefaultStorageFileItem item, ResourceStoreRequest request, @Nonnull String remoteUrl) {
        return new NpmFilteringContentLocator(item.getContentLocator(), request, remoteUrl);
    }

    private ResourceStoreRequest decorateRequest(ResourceStoreRequest request) {
        String path = request.getRequestPath();
        if (!path.endsWith(RepositoryItemUid.PATH_SEPARATOR)) {
            path = path + RepositoryItemUid.PATH_SEPARATOR;
        }
        request.setRequestPath(path + JSON_CONTENT_FILE_NAME);
        return request;
    }

    public final boolean shouldNotGotRemote(ResourceStoreRequest request) {
        return request.getRequestPath().toLowerCase().endsWith(JSON_CONTENT_FILE_NAME);
    }

    public final boolean shouldNotCache(ResourceStoreRequest request) {
        // TODO: This does not work yet, always returns full "all"
        return "/-/all/since".equals(request.getRequestPath());
    }

    static final String NPM_PACKAGE = "npm.package";

    static final String NPM_VERSION = "npm.version";

    /**
     * Adds npm metadata to the request context
     *
     * @param request request we want to decorate
     */
    public void addNpmMeta(@Nonnull ResourceStoreRequest request) {
        String requestPath = request.getRequestPath();
        if (requestPath == null) {
            // wtf?
            return;
        }

        if (RepositoryItemUid.PATH_SEPARATOR.equals(requestPath)) {
            return;
        }

        RequestContext context = request.getRequestContext();

        String correctedPath =
                requestPath.startsWith(RepositoryItemUid.PATH_SEPARATOR) ?
                        requestPath.substring(1, requestPath.length()) :
                        requestPath;

        String[] explodedPath = correctedPath.split(RepositoryItemUid.PATH_SEPARATOR);

        if (explodedPath.length >= 1) {
            context.put(NPM_PACKAGE, explodedPath[0]);
        }

        if (explodedPath.length >= 2) {
            context.put(NPM_VERSION, explodedPath[1]);
        }
    }



    public ResourceStoreRequest hideInCache(ResourceStoreRequest request) {
        request.setRequestPath(HIDDEN_CACHE_PREFIX + request.getRequestPath());
        return request;
    }

    public void processStoreRequest(@Nonnull DefaultStorageFileItem hiddenItem, @Nonnull NpmHostedRepository repository) throws LocalStorageException, UnsupportedStorageOperationException {
        String path = hiddenItem.getPath();

        if ((path == null) || (!path.startsWith(HIDDEN_CACHE_PREFIX))) {
            throw new LocalStorageException("Something went wrong. Publish request was not saved in " + HIDDEN_CACHE_PREFIX);
        }

        // get to real package root
        String packageRoot = path.substring(HIDDEN_CACHE_PREFIX.length(), path.length());

        Gson gson = new Gson();
        try {
            Versions box = gson.fromJson(new InputStreamReader(hiddenItem.getInputStream()), Versions.class);

            if ((box == null) || (box.versions == null) || (box.versions.isEmpty())) {
                throw new LocalStorageException("Unable to extract versions from cached publish request");
            }
            for (String version : box.versions.keySet()) {
                Object o = box.versions.get(version);
                processVersion(packageRoot, version, gson.toJson(o), repository);
            }
        } catch (IOException e) {
            throw new LocalStorageException(e);
        }

    }

    private void processVersion(String packageRoot, String version, String json, NpmHostedRepository repository) throws UnsupportedStorageOperationException, LocalStorageException {
        LocalRepositoryStorage localStorage = repository.getLocalStorage();

        ResourceStoreRequest resourceStoreRequest = new ResourceStoreRequest(packageRoot + RepositoryItemUid.PATH_SEPARATOR + version, true, false);
        ResourceStoreRequest decoratedRequest = decorateRequest(resourceStoreRequest);

        DefaultStorageFileItem item = new DefaultStorageFileItem(repository,
                decoratedRequest,
                true,
                true,
                new NpmJsonContentLocator(json));

        localStorage.storeItem(repository, item);
    }

    /**
     *  Created PackageRequest with commonjs package specific info attached or dies trying.
     *
     * @param request storage request that we suspect to be a package request
     * @return  package request with coordinates and other meta info created
     * @throws
     *          InvalidPackageRequestException if request is not a valid package request as per commonjs spec
     */
    public PackageRequest getPackageRequest(@Nonnull ResourceStoreRequest request)
            throws InvalidPackageRequestException {
        return new PackageRequest(request, this.getCoordinates(request));
    }

    /**
     *  For given package request's content get real storage request. We are mapping json REST-like API onto
     *  filesystem.
     *
     * @param packageRequest request in question
     * @return storage request for content of package request
     */
    public ResourceStoreRequest getContentStorageRequest(@Nonnull PackageRequest packageRequest) {
        ResourceStoreRequest storeRequest = packageRequest.getStoreRequest();

        String path = storeRequest.getRequestPath();
        if (!path.endsWith(RepositoryItemUid.PATH_SEPARATOR)) {
            path = path + RepositoryItemUid.PATH_SEPARATOR;
        }

        return new ResourceStoreRequest(path + JSON_CONTENT_FILE_NAME);
    }

    PackageCoordinates getCoordinates(@Nonnull ResourceStoreRequest request)
            throws InvalidPackageRequestException {
        String requestPath = request.getRequestPath();
        if (requestPath == null) {
            throw new InvalidPackageRequestException("PackageRequest path is null, impossible to determine coordinates");
        }

        if (RepositoryItemUid.PATH_SEPARATOR.equals(requestPath)) {
            return new PackageCoordinates();
        }

        String correctedPath =
                requestPath.startsWith(RepositoryItemUid.PATH_SEPARATOR) ?
                        requestPath.substring(1, requestPath.length()) :
                        requestPath;
        String[] explodedPath = correctedPath.split(RepositoryItemUid.PATH_SEPARATOR);

        if (explodedPath.length == 2) {
            return new PackageCoordinates(explodedPath[0], explodedPath[1]);
        }
        if (explodedPath.length == 1) {
            return new PackageCoordinates(explodedPath[0]);
        }

        throw new InvalidPackageRequestException("Path " + correctedPath + " cannot be turned into PackageCoordinates");
    }
}

class Versions {
    HashMap<String, Object> versions = new HashMap<>();
}
