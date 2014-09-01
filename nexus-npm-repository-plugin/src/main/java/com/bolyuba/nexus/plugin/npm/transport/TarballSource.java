package com.bolyuba.nexus.plugin.npm.transport;

import java.io.IOException;

import com.bolyuba.nexus.plugin.npm.metadata.PackageVersion;
import com.bolyuba.nexus.plugin.npm.proxy.NpmProxyRepository;

/**
 * Transport for getting NPM tarballs, that might be anywhere (the URL pointed by metadata should be used).
 */
public interface TarballSource
{
  /**
   * Unconditionally fetches the tarball for given package version. This call does not perform any conditional
   * checking of remote, as NPM stores the checksum in the metadata (package version), hence, if locally exists
   * the given file, and checksum matches, no need to check on remote for "newer version".
   */
  Tarball get(NpmProxyRepository npmProxyRepository, PackageVersion packageVersion) throws IOException;
}
