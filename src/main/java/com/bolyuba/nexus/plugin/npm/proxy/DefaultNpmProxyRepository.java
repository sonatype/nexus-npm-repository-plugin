package com.bolyuba.nexus.plugin.npm.proxy;

import com.bolyuba.nexus.plugin.npm.NpmContentClass;
import com.bolyuba.nexus.plugin.npm.NpmRepository;
import com.bolyuba.nexus.plugin.npm.pkg.InvalidPackageRequestException;
import com.bolyuba.nexus.plugin.npm.pkg.PackageRequest;
import com.bolyuba.nexus.plugin.npm.proxy.content.NpmFilteringContentLocator;
import com.bolyuba.nexus.plugin.npm.content.NpmMimeRulesSource;
import com.google.inject.Provider;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.sonatype.inject.Description;
import org.sonatype.nexus.configuration.Configurator;
import org.sonatype.nexus.configuration.model.CRepository;
import org.sonatype.nexus.configuration.model.CRepositoryExternalConfigurationHolderFactory;
import org.sonatype.nexus.mime.MimeRulesSource;
import org.sonatype.nexus.proxy.ItemNotFoundException;
import org.sonatype.nexus.proxy.LocalStorageException;
import org.sonatype.nexus.proxy.ResourceStoreRequest;
import org.sonatype.nexus.proxy.item.AbstractStorageItem;
import org.sonatype.nexus.proxy.item.DefaultStorageFileItem;
import org.sonatype.nexus.proxy.item.RepositoryItemUid;
import org.sonatype.nexus.proxy.registry.ContentClass;
import org.sonatype.nexus.proxy.repository.AbstractProxyRepository;
import org.sonatype.nexus.proxy.repository.DefaultRepositoryKind;
import org.sonatype.nexus.proxy.repository.RepositoryKind;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Georgy Bolyuba (georgy@bolyuba.com)
 */
@Named(DefaultNpmProxyRepository.ROLE_HINT)
@Description("Npm registry proxy repo")
public class DefaultNpmProxyRepository
        extends AbstractProxyRepository
        implements NpmProxyRepository, NpmRepository {

    public static final String ROLE_HINT = "npm-proxy";

    private final ContentClass contentClass;

    private final NpmProxyRepositoryConfigurator configurator;

    private final RepositoryKind repositoryKind;

    private final NpmMimeRulesSource mimeRulesSource;

    private final Provider<HttpServletRequest> httpServletRequestProvider;

    @Inject
    public DefaultNpmProxyRepository(final @Named(NpmContentClass.ID) ContentClass contentClass,
                                     final NpmProxyRepositoryConfigurator configurator,
                                     @SuppressWarnings("CdiInjectionPointsInspection") final Provider<HttpServletRequest> httpServletRequestProvider) {

        this.httpServletRequestProvider = checkNotNull(httpServletRequestProvider);
        this.contentClass = checkNotNull(contentClass);
        this.configurator = checkNotNull(configurator);

        this.repositoryKind = new DefaultRepositoryKind(NpmProxyRepository.class, null);
        this.mimeRulesSource = new NpmMimeRulesSource();
    }

    @Override
    protected Configurator getConfigurator() {
        return configurator;
    }

    @Override
    public RepositoryKind getRepositoryKind() {
        return repositoryKind;
    }

    @Override
    public ContentClass getRepositoryContentClass() {
        return contentClass;
    }

    @Override
    protected CRepositoryExternalConfigurationHolderFactory<NpmProxyRepositoryConfiguration> getExternalConfigurationHolderFactory() {
        return new CRepositoryExternalConfigurationHolderFactory<NpmProxyRepositoryConfiguration>() {
            @Override
            public NpmProxyRepositoryConfiguration createExternalConfigurationHolder(final CRepository config) {
                return new NpmProxyRepositoryConfiguration((Xpp3Dom) config.getExternalConfiguration());
            }
        };
    }

    @Override
    public MimeRulesSource getMimeRulesSource() {
        return mimeRulesSource;
    }

    @Override
    protected AbstractStorageItem doRetrieveLocalItem(ResourceStoreRequest storeRequest) throws ItemNotFoundException, LocalStorageException {
        // only care about request if it is coming from npm client
        if (isNmpRequest(storeRequest)) {
            try {
                PackageRequest packageRequest = new PackageRequest(storeRequest);
                if (packageRequest.isPackage()) {
                    return delegateDoRetrieveLocalItem(replaceRequest(storeRequest));
                } else {
                    // huh?
                    return delegateDoRetrieveLocalItem(storeRequest);
                }
            } catch (InvalidPackageRequestException ignore) {
                return delegateDoRetrieveLocalItem(storeRequest);
            }
        } else {
            return delegateDoRetrieveLocalItem(storeRequest);
        }
    }

    @Override
    public AbstractStorageItem doCacheItem(AbstractStorageItem item) throws LocalStorageException {
        try {
            ResourceStoreRequest storeRequest = item.getResourceStoreRequest();
            PackageRequest packageRequest = new PackageRequest(storeRequest);

            if (packageRequest.isPackage()) {
                DefaultStorageFileItem wrappedItem = wrapItem((DefaultStorageFileItem) item);
                return delegateDoCacheItem(wrappedItem);
            } else {
                // no cache 4 u
                return item;
            }
        } catch (InvalidPackageRequestException ignore) {
            // do it old style
            return delegateDoCacheItem(item);
        }
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
    boolean isNmpRequest(@SuppressWarnings("UnusedParameters") ResourceStoreRequest request) {

        HttpServletRequest httpServletRequest = httpServletRequestProvider.get();
        if (httpServletRequest == null) {
            return false;
        }

        String accept = httpServletRequest.getHeader("accept");

        return accept != null && accept.toLowerCase().equals(JSON_MIME_TYPE);
    }


    DefaultStorageFileItem wrapItem(DefaultStorageFileItem item) {
        ResourceStoreRequest request = item.getResourceStoreRequest();

        NpmFilteringContentLocator decoratedContentLocator =
                new NpmFilteringContentLocator(item.getContentLocator(), request, this.getRemoteUrl());

        wrapRequest(request);

        return getWrappedStorageFileItem(item, decoratedContentLocator, request);
    }

    ResourceStoreRequest wrapRequest(ResourceStoreRequest request) {
        String path = getCorrectedRequestPath(request);
        request.setRequestPath(path);
        return request;
    }

    ResourceStoreRequest replaceRequest(ResourceStoreRequest request) {
        String path = getCorrectedRequestPath(request);
        return new ResourceStoreRequest(path);
    }

    String getCorrectedRequestPath(ResourceStoreRequest request) {
        String path = request.getRequestPath();
        if (!path.endsWith(RepositoryItemUid.PATH_SEPARATOR)) {
            path = path + RepositoryItemUid.PATH_SEPARATOR;
        }
        return path + JSON_CONTENT_FILE_NAME;
    }

    // tests to mock these methods
    DefaultStorageFileItem getWrappedStorageFileItem(DefaultStorageFileItem item, NpmFilteringContentLocator decoratedContentLocator, ResourceStoreRequest decoratedRequest) {
        return new DefaultStorageFileItem(
                this,
                decoratedRequest,
                item.isReadable(),
                item.isWritable(),
                decoratedContentLocator);
    }

    AbstractStorageItem delegateDoCacheItem(AbstractStorageItem item) throws LocalStorageException {
        return super.doCacheItem(item);
    }

    AbstractStorageItem delegateDoRetrieveLocalItem(ResourceStoreRequest storeRequest) throws LocalStorageException, ItemNotFoundException {
        return super.doRetrieveLocalItem(storeRequest);
    }
}