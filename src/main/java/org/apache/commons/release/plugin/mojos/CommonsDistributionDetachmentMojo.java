/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.release.plugin.mojos;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.release.plugin.SharedFunctions;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * The purpose of this maven mojo is to detach the artifacts generated by the maven-assembly-plugin,
 * which for the Apache Commons Project do not get uploaded to Nexus, and putting those artifacts
 * in the dev distribution location for apache projects.
 *
 * @author chtompki
 * @since 1.0
 */
@Mojo(name = "detach-distributions",
        defaultPhase = LifecyclePhase.VERIFY,
        threadSafe = true,
        aggregator = true)
public class CommonsDistributionDetachmentMojo extends AbstractMojo {

    /**
     * A list of "artifact types" in the maven vernacular, to
     * be detached from the deployment. For the time being we want
     * all artifacts generated by the maven-assembly-plugin to be detached
     * from the deployment, namely *-src.zip, *-src.tar.gz, *-bin.zip,
     * *-bin.tar.gz, and the corresponding .asc pgp signatures.
     */
    private static final Set<String> ARTIFACT_TYPES_TO_DETACH;
    static {
        Set<String> hashSet = new HashSet<>();
        hashSet.add("zip");
        hashSet.add("tar.gz");
        hashSet.add("zip.asc");
        hashSet.add("tar.gz.asc");
        ARTIFACT_TYPES_TO_DETACH = Collections.unmodifiableSet(hashSet);
    }

    /**
     * This list is supposed to hold the maven references to the aformentioned artifacts so that we
     * can upload them to svn after they've been detached from the maven deployment.
     */
    private List<Artifact> detachedArtifacts = new ArrayList<>();

    /**
     * A {@link Properties} of {@link Artifact} → {@link String} containing the sha1 signatures
     * for the individual artifacts, where the {@link Artifact} is represented as:
     * <code>groupId:artifactId:version:type=sha1</code>.
     */
    private Properties artifactSha1s = new Properties();

    /**
     * The maven project context injection so that we can get a hold of the variables at hand.
     */
    @Parameter(defaultValue = "${project}", required = true)
    private MavenProject project;

    /**
     * The working directory in <code>target</code> that we use as a sandbox for the plugin.
     */
    @Parameter(defaultValue = "${project.build.directory}/commons-release-plugin",
            property = "commons.outputDirectory")
    private File workingDirectory;

    /**
     * The subversion staging url to which we upload all of our staged artifacts.
     */
    @Parameter(defaultValue = "", property = "commons.distSvnStagingUrl")
    private String distSvnStagingUrl;

    /**
     * A parameter to generally avoid running unless it is specifically turned on by the consuming module.
     */
    @Parameter(defaultValue = "false", property = "commons.release.isDistModule")
    private Boolean isDistModule;

    @Override
    public void execute() throws MojoExecutionException {
        if (!isDistModule) {
            getLog().info("This module is marked as a non distribution "
                    + "or assembly module, and the plugin will not run.");
            return;
        }
        if (StringUtils.isEmpty(distSvnStagingUrl)) {
            getLog().warn("commons.distSvnStagingUrl is not set, the commons-release-plugin will not run.");
            return;
        }
        getLog().info("Detaching Assemblies");
        for (Object attachedArtifact : project.getAttachedArtifacts()) {
            putAttachedArtifactInSha1Map((Artifact) attachedArtifact);
            if (ARTIFACT_TYPES_TO_DETACH.contains(((Artifact) attachedArtifact).getType())) {
                detachedArtifacts.add((Artifact) attachedArtifact);
            }
        }
        if (detachedArtifacts.isEmpty()) {
            getLog().info("Current project contains no distributions. Not executing.");
            return;
        }
        for (Artifact artifactToRemove : detachedArtifacts) {
            project.getAttachedArtifacts().remove(artifactToRemove);
        }
        if (!workingDirectory.exists()) {
            SharedFunctions.initDirectory(getLog(), workingDirectory);
        }
        logAllArtifactsInPropertiesFile();
        copyRemovedArtifactsToWorkingDirectory();
        getLog().info("");
        sha1AndMd5SignArtifacts();
    }

    /**
     * Takes an attached artifact and puts the signature in the map.
     * @param artifact is a maven {@link Artifact} taken from the project at start time of mojo.
     * @throws MojoExecutionException if an {@link IOException} occurs when getting the sha1 of the
     *                                artifact.
     */
    private void putAttachedArtifactInSha1Map(Artifact artifact) throws MojoExecutionException {
        try {
            StringBuffer artifactKey = new StringBuffer();
            artifactKey
                .append(artifact.getGroupId()).append('-')
                .append(artifact.getArtifactId()).append('-')
                .append(artifact.getVersion()).append('-')
                .append(artifact.getType());
            artifactSha1s.put(
                artifactKey.toString(),
                DigestUtils.sha1Hex(Files.readAllBytes(artifact.getFile().toPath()))
            );
        } catch (IOException e) {
            throw new MojoExecutionException(
                "Could not find artifact signature for: "
                    + artifact.getArtifactId()
                    + "-"
                    + artifact.getVersion()
                    + " type: "
                    + artifact.getType()
                ,e);
        }
    }

    /**
     * Writes to ./target/commons-release-plugin/sha1.properties the artifact sha1's.
     *
     * @throws MojoExecutionException if we cant write the file due to an {@link IOException}.
     */
    private void logAllArtifactsInPropertiesFile() throws MojoExecutionException {
        try {
            File sha1PropertiesFile = new File(workingDirectory, "sha1.properties");
            FileOutputStream fileWriter = new FileOutputStream(sha1PropertiesFile);
            artifactSha1s.store(fileWriter, "release sha1s");
        } catch (IOException e) {
            throw new MojoExecutionException("Failure to write sha1's", e);
        }
    }

    /**
     * A helper method to copy the newly detached artifacts to <code>target/commons-release-plugin</code>
     * so that the {@link CommonsDistributionStagingMojo} can find the artifacts later.
     *
     * @throws MojoExecutionException if some form of an {@link IOException} occurs, we want it
     *                                properly wrapped so that maven can handle it.
     */
    private void copyRemovedArtifactsToWorkingDirectory() throws MojoExecutionException {
        StringBuffer copiedArtifactAbsolutePath;
        getLog().info("Copying detached artifacts to working directory.");
        for (Artifact artifact: detachedArtifacts) {
            File artifactFile = artifact.getFile();
            copiedArtifactAbsolutePath = new StringBuffer(workingDirectory.getAbsolutePath());
            copiedArtifactAbsolutePath.append("/");
            copiedArtifactAbsolutePath.append(artifactFile.getName());
            File copiedArtifact = new File(copiedArtifactAbsolutePath.toString());
            getLog().info("Copying: " + artifactFile.getName());
            SharedFunctions.copyFile(getLog(), artifactFile, copiedArtifact);
        }
    }

    /**
     *  A helper method that creates md5 and sha1 signature files for our detached artifacts in the
     *  <code>target/commons-release-plugin</code> directory for the purpose of being uploade by
     *  the {@link CommonsDistributionStagingMojo}.
     *
     * @throws MojoExecutionException if some form of an {@link IOException} occurs, we want it
     *                                properly wrapped so that maven can handle it.
     */
    private void sha1AndMd5SignArtifacts() throws MojoExecutionException {
        for (Artifact artifact : detachedArtifacts) {
            if (!artifact.getFile().getName().contains("asc")) {
                try {
                    String md5 = DigestUtils.md5Hex(Files.readAllBytes(artifact.getFile().toPath()));
                    getLog().info(artifact.getFile().getName() + " md5: " + md5);
                    try (PrintWriter md5Writer = new PrintWriter(getMd5FilePath(workingDirectory, artifact.getFile()))){
                        md5Writer.println(md5);
                    }
                    String sha1 = DigestUtils.sha1Hex(Files.readAllBytes(artifact.getFile().toPath()));
                    getLog().info(artifact.getFile().getName() + " sha1: " + sha1);
                    try (PrintWriter sha1Writer = new PrintWriter(getSha1FilePath(workingDirectory, artifact.getFile()))) {
                        sha1Writer.println(sha1);
                    }
                } catch (IOException e) {
                    throw new MojoExecutionException("Could not sign file: " + artifact.getFile().getName(), e);
                }
            }
        }
    }

    /**
     * A helper method to create a file path for the <code>md5</code> signature file from a given file.
     *
     * @param workingDirectory is the {@link File} for the directory in which to make the <code>.md5</code> file.
     * @param file the {@link File} whose name we should use to create the <code>.md5</code> file.
     * @return a {@link String} that is the absolute path to the <code>.md5</code> file.
     */
    private String getMd5FilePath(File workingDirectory, File file) {
        StringBuffer buffer = new StringBuffer(workingDirectory.getAbsolutePath());
        buffer.append("/");
        buffer.append(file.getName());
        buffer.append(".md5");
        return buffer.toString();
    }

    /**
     * A helper method to create a file path for the <code>sha1</code> signature file from a given file.
     *
     * @param workingDirectory is the {@link File} for the directory in which to make the <code>.sha1</code> file.
     * @param file the {@link File} whose name we should use to create the <code>.sha1</code> file.
     * @return a {@link String} that is the absolute path to the <code>.sha1</code> file.
     */
    private String getSha1FilePath(File workingDirectory, File file) {
        StringBuffer buffer = new StringBuffer(workingDirectory.getAbsolutePath());
        buffer.append("/");
        buffer.append(file.getName());
        buffer.append(".sha1");
        return buffer.toString();
    }
}
