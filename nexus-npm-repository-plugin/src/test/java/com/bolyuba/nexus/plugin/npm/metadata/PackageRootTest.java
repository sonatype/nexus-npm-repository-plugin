package com.bolyuba.nexus.plugin.npm.metadata;

import java.io.File;
import java.util.Map;

import org.sonatype.nexus.proxy.item.FileContentLocator;
import org.sonatype.sisu.litmus.testsupport.TestSupport;

import com.bolyuba.nexus.plugin.npm.NpmRepository;
import com.bolyuba.nexus.plugin.npm.metadata.internal.MetadataParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Charsets;
import com.google.common.io.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.skyscreamer.jsonassert.JSONAssert;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * UT for {@link PackageRoot}
 */
public class PackageRootTest
    extends TestSupport
{
  private final ObjectMapper objectMapper;

  public PackageRootTest() {
    objectMapper = new ObjectMapper();
    objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
  }

  @Test
  public void overlay() throws Exception {
    final Map<String, Object> commonjs1Map = objectMapper
        .readValue(util.resolveFile("src/test/npm/ROOT_commonjs_v1.json"), new TypeReference<Map<String, Object>>() {});
    final PackageRoot commonjs1 = new PackageRoot("repo", commonjs1Map);

    final Map<String, Object> commonjs2Map = objectMapper
        .readValue(util.resolveFile("src/test/npm/ROOT_commonjs_v2.json"), new TypeReference<Map<String, Object>>() {});
    final PackageRoot commonjs2 = new PackageRoot("repo", commonjs2Map);

    final Map<String, Object> commonjs3Map = objectMapper
        .readValue(util.resolveFile("src/test/npm/ROOT_commonjs_vIncomplete.json"),
            new TypeReference<Map<String, Object>>() {});
    final PackageRoot commonjs3 = new PackageRoot("repo", commonjs3Map);

    assertThat(commonjs1.getComponentId(), equalTo(commonjs2.getComponentId()));
    assertThat(commonjs1.getVersions().keySet(), hasItem("0.0.1"));
    assertThat(commonjs1.isIncomplete(), is(false));

    commonjs1.overlay(commonjs2);

    assertThat(commonjs1.getComponentId(), equalTo(commonjs2.getComponentId()));
    assertThat(commonjs1.getVersions().keySet(), hasItems("0.0.1", "0.0.2"));
    assertThat(commonjs1.isIncomplete(), is(false));

    commonjs1.overlay(commonjs3);

    assertThat(commonjs1.getVersions().keySet(), hasItems("0.0.1", "0.0.2", "0.0.3"));
    assertThat(commonjs1.isIncomplete(), is(true));

    // objectMapper.writeValue(System.out, commonjs1.getRaw());
  }

  @Test
  public void attachmentExtraction() throws Exception {
    final NpmRepository npmRepository = mock(NpmRepository.class);
    when(npmRepository.getId()).thenReturn("repo");
    final File uploadRequest = util.resolveFile("src/test/npm/ROOT_testproject.json");

    final File tmpDir = util.createTempDir();

    final MetadataParser parser = new MetadataParser(tmpDir);
    final PackageRoot root = parser
        .parsePackageRoot(npmRepository.getId(), new FileContentLocator(uploadRequest, NpmRepository.JSON_MIME_TYPE));

    assertThat(root.getAttachments().size(), is(1));
    assertThat(root.getAttachments(), hasKey("testproject-0.0.0.tgz"));
    final PackageAttachment attachment = root.getAttachments().get("testproject-0.0.0.tgz");
    assertThat(attachment.getName(), is("testproject-0.0.0.tgz"));
    assertThat(attachment.getMimeType(), is("application/octet-stream"));
    assertThat(attachment.getLength(), is(276L));
    assertThat(attachment.getFile().isFile(), is(true));
    assertThat(attachment.getFile().length(), is(276L));

    JSONObject onDisk = new JSONObject(Files.toString(uploadRequest, Charsets.UTF_8));
    onDisk.remove("_attachments"); // omit "attachments" as they are processed separately
    JSONObject onStore = new JSONObject(objectMapper.writeValueAsString(root.getRaw()));
    JSONAssert.assertEquals(onDisk, onStore, false);
  }
}
