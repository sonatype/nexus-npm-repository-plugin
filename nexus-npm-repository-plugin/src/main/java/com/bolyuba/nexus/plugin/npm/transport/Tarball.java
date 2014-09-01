package com.bolyuba.nexus.plugin.npm.transport;

import java.io.File;

import org.sonatype.nexus.proxy.item.FileContentLocator;

import com.bolyuba.nexus.plugin.npm.NpmRepository;
import com.bolyuba.nexus.plugin.npm.metadata.PackageVersion;

/**
 * Tarball with it's associated package version backed by a temp file, hence, the content is NOT reusable.
 */
public class Tarball
    extends FileContentLocator
{
  private final PackageVersion packageVersion;

  private final String sha1sum;

  public Tarball(final File tempFile, final PackageVersion packageVersion, final String sha1sum) {
    super(tempFile, NpmRepository.TARBALL_MIME_TYPE, true);
    this.packageVersion = packageVersion;
    this.sha1sum = sha1sum;
  }

  public PackageVersion getPackageVersion() {
    return packageVersion;
  }

  public String getSha1sum() { return sha1sum; }
}
