package com.bolyuba.nexus.plugin.npm.metadata.internal;

import java.io.IOException;

import javax.annotation.Nullable;

import org.sonatype.nexus.proxy.item.ContentLocator;

import com.bolyuba.nexus.plugin.npm.hosted.NpmHostedRepository;
import com.bolyuba.nexus.plugin.npm.metadata.HostedMetadataService;
import com.bolyuba.nexus.plugin.npm.metadata.PackageRoot;
import com.bolyuba.nexus.plugin.npm.metadata.PackageVersion;
import com.bolyuba.nexus.plugin.npm.pkg.PackageRequest;

/**
 * {@link HostedMetadataService} implementation.
 */
public class HostedMetadataServiceImpl
    extends GeneratorSupport
    implements HostedMetadataService
{
  private final NpmHostedRepository npmHostedRepository;

  private final MetadataGenerator metadataGenerator;

  private final MetadataParser metadataParser;

  public HostedMetadataServiceImpl(final NpmHostedRepository npmHostedRepository,
                                   final MetadataGenerator metadataGenerator,
                                   final MetadataParser metadataParser)
  {
    super(metadataParser);
    this.npmHostedRepository = npmHostedRepository;
    this.metadataGenerator = metadataGenerator;
    this.metadataParser = metadataParser;
  }


  @Override
  public PackageRoot consumePackageRoot(final PackageRequest request, final ContentLocator contentLocator)
      throws IOException
  {
    return metadataGenerator.consumePackageRoot(
        metadataParser.parsePackageRoot(npmHostedRepository.getId(), contentLocator));
  }

  @Override
  protected PackageRootIterator doGenerateRegistryRoot(final PackageRequest request) throws IOException {
    return metadataGenerator.generateRegistryRoot();
  }

  @Nullable
  @Override
  protected PackageRoot doGeneratePackageRoot(final PackageRequest request) throws IOException {
    return metadataGenerator.generatePackageRoot(request.getName());
  }

  @Nullable
  @Override
  protected PackageVersion doGeneratePackageVersion(final PackageRequest request) throws IOException {
    return metadataGenerator.generatePackageVersion(request.getName(),
        request.getVersion());
  }
}
