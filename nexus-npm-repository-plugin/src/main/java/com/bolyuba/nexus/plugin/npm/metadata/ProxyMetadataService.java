package com.bolyuba.nexus.plugin.npm.metadata;

import java.io.IOException;

import javax.annotation.Nullable;

import com.bolyuba.nexus.plugin.npm.pkg.PackageRequest;

/**
 * Metadata service for proxy repositories. Component generating NPM metadata from underlying store to
 * be sent downstream for consumption by NPM CLI or alike. Still, unlike "plain" generator, this
 * component ensures first that the store contains up to date data and serves that.
 */
public interface ProxyMetadataService
    extends Generator
{
  /**
   * Expires proxy metadata cache. On next request of an expired metadata, refetch will be done from registry.
   */
  boolean expireMetadataCaches(PackageRequest request);

  /**
   * Returns corresponding package root for given package name in <strong>raw form</strong>, as it was sent to us by
   * the proxied registry, or {@link null} if no such package.
   */
  @Nullable
  PackageRoot generateRawPackageRoot(String packageName) throws IOException;
}
