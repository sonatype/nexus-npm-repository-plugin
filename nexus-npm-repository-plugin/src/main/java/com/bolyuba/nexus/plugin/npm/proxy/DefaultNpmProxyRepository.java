package com.bolyuba.nexus.plugin.npm.proxy;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.bolyuba.nexus.plugin.npm.NpmContentClass;
import com.bolyuba.nexus.plugin.npm.NpmRepository;
import com.bolyuba.nexus.plugin.npm.content.NpmMimeRulesSource;
import com.bolyuba.nexus.plugin.npm.metadata.MetadataServiceFactory;
import com.bolyuba.nexus.plugin.npm.metadata.PackageRoot;
import com.bolyuba.nexus.plugin.npm.metadata.PackageVersion;
import com.bolyuba.nexus.plugin.npm.metadata.ProxyMetadataService;
import com.bolyuba.nexus.plugin.npm.pkg.PackageRequest;
import com.bolyuba.nexus.plugin.npm.transport.Tarball;
import com.bolyuba.nexus.plugin.npm.transport.TarballSource;
import com.google.common.base.Strings;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.sisu.Description;

import org.sonatype.nexus.configuration.Configurator;
import org.sonatype.nexus.configuration.model.CRepository;
import org.sonatype.nexus.configuration.model.CRepositoryExternalConfigurationHolderFactory;
import org.sonatype.nexus.mime.MimeRulesSource;
import org.sonatype.nexus.proxy.IllegalOperationException;
import org.sonatype.nexus.proxy.ItemNotFoundException;
import org.sonatype.nexus.proxy.LocalStorageException;
import org.sonatype.nexus.proxy.RemoteAccessException;
import org.sonatype.nexus.proxy.RemoteStorageException;
import org.sonatype.nexus.proxy.ResourceStoreRequest;
import org.sonatype.nexus.proxy.StorageException;
import org.sonatype.nexus.proxy.item.AbstractStorageItem;
import org.sonatype.nexus.proxy.item.ContentLocator;
import org.sonatype.nexus.proxy.item.DefaultStorageFileItem;
import org.sonatype.nexus.proxy.item.StorageFileItem;
import org.sonatype.nexus.proxy.item.StorageItem;
import org.sonatype.nexus.proxy.registry.ContentClass;
import org.sonatype.nexus.proxy.repository.AbstractProxyRepository;
import org.sonatype.nexus.proxy.repository.DefaultRepositoryKind;
import org.sonatype.nexus.proxy.repository.Repository;
import org.sonatype.nexus.proxy.repository.RepositoryKind;
import org.sonatype.nexus.proxy.walker.WalkerFilter;

import javax.enterprise.inject.Typed;
import javax.inject.Inject;
import javax.inject.Named;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.proxy.ItemNotFoundException.reasonFor;

/**
 * @author Georgy Bolyuba (georgy@bolyuba.com)
 */
@Named(DefaultNpmProxyRepository.ROLE_HINT)
@Typed(Repository.class)
@Description("Npm registry proxy repo")
public class DefaultNpmProxyRepository
        extends AbstractProxyRepository
        implements NpmProxyRepository, Repository {

    public static final String ROLE_HINT = "npm-proxy";

    private final ContentClass contentClass;

    private final NpmProxyRepositoryConfigurator configurator;

    private final RepositoryKind repositoryKind;

    private final NpmMimeRulesSource mimeRulesSource;

    private final ProxyMetadataService proxyMetadataService;

    private final TarballSource tarballSource;

    @Inject
    public DefaultNpmProxyRepository(final @Named(NpmContentClass.ID) ContentClass contentClass,
                                     final NpmProxyRepositoryConfigurator configurator,
                                     final MetadataServiceFactory metadataServiceFactory,
                                     final TarballSource tarballSource) {

        this.proxyMetadataService = metadataServiceFactory.createProxyMetadataService(this);
        this.tarballSource = checkNotNull(tarballSource);
        this.contentClass = checkNotNull(contentClass);
        this.configurator = checkNotNull(configurator);

        this.repositoryKind = new DefaultRepositoryKind(NpmProxyRepository.class, null);
        this.mimeRulesSource = new NpmMimeRulesSource();
    }

    @Override
    public ProxyMetadataService getMetadataService() { return proxyMetadataService; }

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
    protected boolean doExpireProxyCaches(final ResourceStoreRequest request, final WalkerFilter filter) {
        boolean result = super.doExpireProxyCaches(request, filter);
        try {
          boolean npmResult = proxyMetadataService.expireMetadataCaches(new PackageRequest(request));
          return result || npmResult;
        } catch (IllegalArgumentException ignore) {
          // ignore
          return result;
        }
    }

    @Override
    protected AbstractStorageItem doRetrieveLocalItem(ResourceStoreRequest storeRequest) throws ItemNotFoundException, LocalStorageException {
        try {
          try {
            PackageRequest packageRequest = new PackageRequest(storeRequest);
            packageRequest.getStoreRequest().getRequestContext().put(NpmRepository.NPM_METADATA_SERVICED, Boolean.TRUE);
            if (packageRequest.isMetadata()) {
              ContentLocator contentLocator;
              if (packageRequest.isRegistryRoot()) {
                log.debug("Serving registry root...");
                contentLocator = proxyMetadataService.getProducer().produceRegistryRoot(packageRequest);
              }
              else if (packageRequest.isPackageRoot()) {
                log.debug("Serving package {} root...", packageRequest.getName());
                contentLocator = proxyMetadataService.getProducer().producePackageRoot(packageRequest);
              }
              else {
                log.debug("Serving package {} version {}...", packageRequest.getName(), packageRequest.getVersion());
                contentLocator = proxyMetadataService.getProducer().producePackageVersion(packageRequest);
              }
              if (contentLocator == null) {
                log.debug("No NPM metadata for path {}", storeRequest.getRequestPath());
                throw new ItemNotFoundException(
                    reasonFor(storeRequest, this, "No content for path %s", storeRequest.getRequestPath()));
              }
              return createStorageFileItem(storeRequest, contentLocator);
            }
            else {
              // registry special
              if (packageRequest.isRegistrySpecial() && packageRequest.getPath().startsWith("/-/all")) {
                log.debug("Serving registry root from /-/all...");
                return createStorageFileItem(storeRequest,
                    proxyMetadataService.getProducer().produceRegistryRoot(packageRequest));
              }
              throw new ItemNotFoundException(
                  reasonFor(storeRequest, this, "No content for path %s", storeRequest.getRequestPath()));
            }
          }
          catch (IllegalArgumentException ignore) {
            // ignore, will do it standard way if needed
          }
          // this must be tarball, check it out do we have it locally, and if yes, and metadata checksum matches, give it
          final PackageVersion packageVersion = getPackageVersionForTarballRequest(storeRequest);
          if (packageVersion != null) {
            try {
              final AbstractStorageItem item = getLocalStorage().retrieveItem(this, storeRequest);
              if (item instanceof StorageFileItem) {
                if (!Strings.isNullOrEmpty(packageVersion.getDistShasum()) && !packageVersion.getDistShasum().equals(item.getRepositoryItemAttributes().get(StorageFileItem.DIGEST_SHA1_KEY))) {
                  // we have it and is up to date (hash matches metadata)
                  item.setRemoteChecked(Long.MAX_VALUE);
                  item.setExpired(false);
                  return item;
                }
              }
            } catch (ItemNotFoundException e) {
              // no problem, just continue then
            }
          }
          throw new ItemNotFoundException(
              reasonFor(storeRequest, this, "No local content for path %s", storeRequest.getRequestPath()));
        } catch (IOException e) {
          throw new LocalStorageException("Metadata service error", e);
        }
    }

    /**
     * Prepares a file item. The "catch" is that this is proxy repository, and we don't want to have NX core aging
     * interfere with proxying of NPM metadata service, so whatever we have here we mark as "fresh" to not have
     * proxy logic of core kick in redoing all the generation again. To core, this file item looks like coming
     * from local store (cache), hence "aging" will be applied.
     */
    private DefaultStorageFileItem createStorageFileItem(final ResourceStoreRequest storeRequest, final ContentLocator contentLocator) {
      final DefaultStorageFileItem result = new DefaultStorageFileItem(this, storeRequest, true, true, contentLocator);
      result.setRemoteChecked(Long.MAX_VALUE); // do not handle it as expired at any cost
      result.setExpired(false); // do not handle it as expired at any cost
      return result;
    }

    /**
     * Beside original behaviour, only try remote the non-metadata requests.
     */
    @Override
    protected void shouldTryRemote(final ResourceStoreRequest request)
        throws IllegalOperationException, ItemNotFoundException
    {
      super.shouldTryRemote(request);
      if (request.getRequestContext().containsKey(NpmRepository.NPM_METADATA_SERVICED)) {
        throw new ItemNotFoundException(ItemNotFoundException.reasonFor(request, this,
            "Request is serviced by NPM metadata service, remote access not needed from %s", this));
      }
    }

    /**
     * Beside original behavior, only add to NFC non-metadata requests.
     */
    @Override
    protected boolean shouldAddToNotFoundCache(final ResourceStoreRequest request) {
      boolean shouldAddToNFC = super.shouldAddToNotFoundCache(request);
      if (shouldAddToNFC) {
        return !request.getRequestContext().containsKey(NpmRepository.NPM_METADATA_SERVICED);
      }
      return shouldAddToNFC;
    }

    @Override
    public AbstractStorageItem doCacheItem(AbstractStorageItem item) throws LocalStorageException {
        try {
            ResourceStoreRequest storeRequest = item.getResourceStoreRequest();
            PackageRequest packageRequest = new PackageRequest(storeRequest);
            log.info("NPM cache {}", packageRequest.getPath());
            if (packageRequest.isMetadata()) {
              // no cache, is done by MetadataService (should not get here at all)
              return item;
            } else {
              return delegateDoCacheItem(item);
            }
        } catch (IllegalArgumentException ignore) {
            // do it old style
            return delegateDoCacheItem(item);
        }
    }

    AbstractStorageItem delegateDoCacheItem(AbstractStorageItem item) throws LocalStorageException {
        return super.doCacheItem(item);
    }

    AbstractStorageItem delegateDoRetrieveLocalItem(ResourceStoreRequest storeRequest) throws LocalStorageException, ItemNotFoundException {
        return super.doRetrieveLocalItem(storeRequest);
    }

    /**
     * Regex for tarball requests. They are in form of {@code /pkgName/-/pkgName-pkgVersion.tgz}, with a catch, that
     * pkgVersion might be suffixed by some suffix (ie. "beta", "alpha", etc). Groups in regexp: 1. the "packageName",
     * 2. the complete filename after "/-/"
     */
    private final static Pattern TARBALL_PATH_PATTERN = Pattern.compile("/([[a-z][A-Z][0-9]-_\\.]+)/-/([[a-z][A-Z][0-9]-_\\.]+\\.tgz)");

    @Override
    protected AbstractStorageItem doRetrieveRemoteItem(final ResourceStoreRequest request)
        throws ItemNotFoundException, RemoteAccessException, StorageException
    {
      final PackageVersion packageVersion;
      try {
        packageVersion = getPackageVersionForTarballRequest(request);
      } catch (IOException e) {
        throw new RemoteStorageException("NPM Metadata service error", e);
      }
      if (packageVersion != null) {
        try {
          final Tarball tarball = tarballSource.get(this, packageVersion);
          if (tarball!= null) {
            final DefaultStorageFileItem result = new DefaultStorageFileItem(this, request, true, true, tarball);
            // stash in SHA1 sum as we have it already
            result.getItemContext().put(StorageFileItem.DIGEST_SHA1_KEY, tarball.getSha1sum());
            return result;
          }
          throw new ItemNotFoundException(ItemNotFoundException.reasonFor(request, this,
              "Request cannot be serviced by NPM proxy %s: tarball for package %s version %s not found", this,
              packageVersion.getName(), packageVersion.getVersion()));
        } catch (IOException e) {
          throw new RemoteStorageException("NPM TarballSource service error", e);
        }
      } else {
        throw new ItemNotFoundException(ItemNotFoundException.reasonFor(request, this,
            "Request cannot be serviced by NPM proxy %s: tarball package not found", this));
      }
    }

  /**
   * Retrieves the corresponding {@link PackageVersion} for given <strong>tarball request</strong>. If not found, or
   * request is not a tarball request, returns {@code null}.
   */
  protected PackageVersion getPackageVersionForTarballRequest(final ResourceStoreRequest request) throws IOException {
    final Matcher matcher = TARBALL_PATH_PATTERN.matcher(request.getRequestPath());
    if (matcher.matches()) {
      final String requestedPackageName = matcher.group(1);
      final String requestedTarballFilename = matcher.group(2);
      final PackageRoot packageRoot = getMetadataService().generateRawPackageRoot(requestedPackageName);
      if (packageRoot != null) {
        for (PackageVersion version : packageRoot.getVersions().values()) {
          // TODO: simpler regex and filename matching used for simplicity's sake. Version could be extracted
          // as it was done in prev PR, but it's probably more fragile than this. Anyway, the requested path contains
          // the filename, so we can match using endsWith based on packageVersion dist tarball
          if (version.getDistTarball().endsWith(requestedTarballFilename)) {
            return version;
          }
        }
      }
    }
    return null;
  }
}