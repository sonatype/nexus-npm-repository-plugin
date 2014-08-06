package com.bolyuba.nexus.plugin.npm.metadata.internal;

import java.io.IOException;

import javax.annotation.Nullable;

import org.sonatype.nexus.proxy.item.ContentLocator;

import com.bolyuba.nexus.plugin.npm.metadata.Generator;
import com.bolyuba.nexus.plugin.npm.metadata.Producer;
import com.bolyuba.nexus.plugin.npm.pkg.PackageRequest;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Producer of "raw" streamed content from {@link Generator}s.
 */
public class GeneratorProducerImpl
    implements Producer
{
  private final Generator generator;

  private final MetadataParser metadataParser;

  public GeneratorProducerImpl(final Generator generator,
                               final MetadataParser metadataParser)
  {
    this.generator = checkNotNull(generator);
    this.metadataParser = checkNotNull(metadataParser);
  }

  @Override
  public ContentLocator produceRegistryRoot(final PackageRequest request) throws IOException {
    return metadataParser.produceRegistryRoot(generator.generateRegistryRoot(request));
  }

  @Nullable
  @Override
  public ContentLocator producePackageRoot(final PackageRequest request) throws IOException {
    return metadataParser.producePackageRoot(generator.generatePackageRoot(request));

  }

  @Nullable
  @Override
  public ContentLocator producePackageVersion(final PackageRequest request) throws IOException {
    return metadataParser.producePackageVersion(generator.generatePackageVersion(request));
  }
}
