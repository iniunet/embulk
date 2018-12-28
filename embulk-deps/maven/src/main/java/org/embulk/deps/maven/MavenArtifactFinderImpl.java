package org.embulk.deps.maven;

import java.io.FileNotFoundException;
import java.io.IOError;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

public class MavenArtifactFinderImpl extends MavenArtifactFinder {
    public MavenArtifactFinderImpl(final Path localMavenRepositoryPath) throws FileNotFoundException {
        final Path absolutePath;
        try {
            absolutePath = localMavenRepositoryPath.normalize().toAbsolutePath();
        } catch (IOError ex) {
            throw new MavenRepositoryNotFoundException(localMavenRepositoryPath, ex);
        } catch (SecurityException ex) {
            throw new MavenRepositoryNotFoundException(localMavenRepositoryPath, ex);
        }

        if (!Files.exists(absolutePath)) {
            throw new MavenRepositoryNotFoundException(localMavenRepositoryPath,
                                                       absolutePath,
                                                       new NoSuchFileException(absolutePath.toString()));
        }
        if (!Files.isDirectory(absolutePath)) {
            throw new MavenRepositoryNotFoundException(localMavenRepositoryPath,
                                                       absolutePath,
                                                       new NotDirectoryException(absolutePath.toString()));
        }

        final RepositorySystem repositorySystem = createRepositorySystem();

        this.givenLocalMavenRepositoryPath = localMavenRepositoryPath;
        this.absoluteLocalMavenRepositoryPath = absolutePath;
        this.repositorySystem = repositorySystem;
        this.repositorySystemSession = createRepositorySystemSession(repositorySystem, absolutePath);
    }

    @Override
    public final MavenPluginPaths findMavenPluginJarsWithDirectDependencies(
            final String groupId,
            final String artifactId,
            final String classifier,
            final String version)
            throws FileNotFoundException {
        final ArtifactDescriptorResult result;
        try {
            result = this.describeMavenArtifact(groupId, artifactId, classifier, "jar", version);
        } catch (ArtifactDescriptorException ex) {
            throw new MavenArtifactNotFoundException(groupId, artifactId, classifier, version,
                                                     this.givenLocalMavenRepositoryPath,
                                                     this.absoluteLocalMavenRepositoryPath,
                                                     ex);
        }
        final ArrayList<Path> dependencyPaths = new ArrayList<>();
        for (final Dependency dependency : result.getDependencies()) {
            final Path dependencyPath = this.findMavenArtifact(dependency.getArtifact());
            dependencyPaths.add(dependencyPath);
        }
        final Path artifactPath = this.findMavenArtifact(result.getArtifact());
        return MavenPluginPaths.of(artifactPath, dependencyPaths);
    }

    private Path findMavenArtifact(final Artifact artifact) throws MavenArtifactNotFoundException {
        final ArtifactResult result;
        try {
            result = this.repositorySystem.resolveArtifact(
                    this.repositorySystemSession, new ArtifactRequest().setArtifact(artifact));
        } catch (ArtifactResolutionException ex) {
            throw new MavenArtifactNotFoundException(artifact,
                                                     this.givenLocalMavenRepositoryPath,
                                                     this.absoluteLocalMavenRepositoryPath,
                                                     ex);
        }
        return result.getArtifact().getFile().toPath();
    }

    private ArtifactDescriptorResult describeMavenArtifact(
            final String groupId,
            final String artifactId,
            final String classifier,
            final String extension,
            final String version)
            throws ArtifactDescriptorException {
        // |classifier| can be null for |org.eclipse.aether.artifact.DefaultArtifact|.
        final ArtifactDescriptorRequest descriptorRequest = new ArtifactDescriptorRequest()
                .setArtifact(new DefaultArtifact(groupId, artifactId, classifier, extension, version));

        return this.repositorySystem.readArtifactDescriptor(this.repositorySystemSession, descriptorRequest);
    }

    private static RepositorySystem createRepositorySystem() {
        final DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        return locator.getService(RepositorySystem.class);
    }

    private static RepositorySystemSession createRepositorySystemSession(
            final RepositorySystem repositorySystem, final Path localRepositoryPath) {
        final DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

        final LocalRepository repository = new LocalRepository(localRepositoryPath.toString());
        session.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(session, repository));
        return session;
    }

    // Paths are kept just for hinting in Exceptions.
    private final Path givenLocalMavenRepositoryPath;
    private final Path absoluteLocalMavenRepositoryPath;

    private final RepositorySystem repositorySystem;
    private final RepositorySystemSession repositorySystemSession;
}
