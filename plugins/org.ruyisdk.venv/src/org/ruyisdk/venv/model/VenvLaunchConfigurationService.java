package org.ruyisdk.venv.model;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.eclipse.cdt.debug.core.ICDTLaunchConfigurationConstants;
import org.eclipse.cdt.managedbuilder.core.IConfiguration;
import org.eclipse.cdt.managedbuilder.core.ManagedBuildManager;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;

/** Creates or updates launch configurations for venv-backed run/debug. */
public class VenvLaunchConfigurationService {

    private static final String ATTR_MANAGED = "org.ruyisdk.launch.managed";
    private static final String ATTR_KIND = "org.ruyisdk.launch.kind";
    private static final String ATTR_VENV_PATH = "org.ruyisdk.launch.venvPath";
    private static final String ATTR_VENV_NAME = "org.ruyisdk.launch.venvName";
    private static final String ATTR_PROJECT_PATH = "org.ruyisdk.launch.projectPath";
    private static final String ATTR_SCHEMA_VERSION = "org.ruyisdk.launch.schemaVersion";
    private static final int SCHEMA_VERSION = 1;
    private static final String RUN_KIND = "run";
    private static final String DEBUG_KIND = "debug";
    private static final String DEFAULT_DEBUG_PORT = "1234";
    private static final String RUN_LAUNCH_TYPE = "org.eclipse.cdt.launch.applicationLaunchType";
    private static final String DEBUG_LAUNCH_TYPE =
            "org.eclipse.cdt.launch.remoteApplicationLaunchType";

    /** Result for launch configuration updates. */
    public static final class LaunchConfigResult {
        private final String runConfigName;
        private final String debugConfigName;

        LaunchConfigResult(String runConfigName, String debugConfigName) {
            this.runConfigName = runConfigName;
            this.debugConfigName = debugConfigName;
        }

        public String getRunConfigName() {
            return runConfigName;
        }

        public String getDebugConfigName() {
            return debugConfigName;
        }
    }

    /** Creates or updates run/debug launch configurations for the given project + venv. */
    public LaunchConfigResult addLaunchConfigs(IProject project, Venv venv) throws CoreException {
        if (project == null || venv == null || venv.getEmulatorExecutableName().isEmpty()) {
            return new LaunchConfigResult("", "");
        }

        final var artifact = resolveArtifact(project, venv);
        if (artifact.isEmpty()) {
            throw new CoreException(Status
                    .error("Unable to resolve build artifact for project: " + project.getName()));
        }

        final var runName = buildConfigName(project, venv, true);
        final var debugName = buildConfigName(project, venv, false);
        final var gdbPath = findGdbPath(venv);

        upsertRunConfig(project, venv, artifact, runName);
        if (gdbPath.isPresent()) {
            upsertDebugConfig(project, venv, artifact, debugName, gdbPath.get());
        }
        return new LaunchConfigResult(runName, gdbPath.isPresent() ? debugName : "");
    }

    private void upsertRunConfig(IProject project, Venv venv, ArtifactInfo artifact,
            String configName) throws CoreException {
        final var launchConfig = getOrCreate(configName, RUN_LAUNCH_TYPE);
        applyCommonAttributes(launchConfig, project, venv, RUN_KIND);
        launchConfig.setAttribute(ICDTLaunchConfigurationConstants.ATTR_PROJECT_NAME,
                project.getName());
        launchConfig.setAttribute(ICDTLaunchConfigurationConstants.ATTR_PROGRAM_NAME,
                venv.getEmulatorDirPath() + File.separator + venv.getEmulatorExecutableName());
        launchConfig.setAttribute(ICDTLaunchConfigurationConstants.ATTR_WORKING_DIRECTORY,
                project.getLocation().toOSString());
        launchConfig.setAttribute(ICDTLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS,
                buildRunArguments(artifact.absolutePath(), venv.getSysroot()));
        launchConfig.setAttribute(ICDTLaunchConfigurationConstants.ATTR_PROJECT_BUILD_CONFIG_AUTO,
                true);
        if (!artifact.configId().isEmpty()) {
            launchConfig.setAttribute(ICDTLaunchConfigurationConstants.ATTR_PROJECT_BUILD_CONFIG_ID,
                    artifact.configId());
        }
        mapProject(launchConfig, project);
        launchConfig.doSave();
    }

    private void upsertDebugConfig(IProject project, Venv venv, ArtifactInfo artifact,
            String configName, String gdbPath) throws CoreException {
        final var launchConfig = getOrCreate(configName, DEBUG_LAUNCH_TYPE);
        applyCommonAttributes(launchConfig, project, venv, DEBUG_KIND);
        launchConfig.setAttribute(ICDTLaunchConfigurationConstants.ATTR_PROJECT_NAME,
                project.getName());
        launchConfig.setAttribute(ICDTLaunchConfigurationConstants.ATTR_PROGRAM_NAME,
                artifact.relativePath());
        launchConfig.setAttribute(ICDTLaunchConfigurationConstants.ATTR_WORKING_DIRECTORY,
                project.getLocation().toOSString());
        launchConfig.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_ID, "gdbserver");
        launchConfig.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_START_MODE,
                "remote");
        launchConfig.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_STOP_AT_MAIN,
                true);
        launchConfig.setAttribute(
                ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_STOP_AT_MAIN_SYMBOL, "main");
        launchConfig.setAttribute(ICDTLaunchConfigurationConstants.ATTR_PROJECT_BUILD_CONFIG_AUTO,
                true);
        if (!artifact.configId().isEmpty()) {
            launchConfig.setAttribute(ICDTLaunchConfigurationConstants.ATTR_PROJECT_BUILD_CONFIG_ID,
                    artifact.configId());
        }
        launchConfig.setAttribute("org.eclipse.cdt.dsf.gdb.DEBUG_NAME", gdbPath);
        launchConfig.setAttribute("org.eclipse.debug.core.ATTR_GDBSERVER_COMMAND", "/bin/bash");
        launchConfig.setAttribute("org.eclipse.debug.core.ATTR_GDBSERVER_PORT", DEFAULT_DEBUG_PORT);
        launchConfig.setAttribute("org.eclipse.debug.core.ATTR_GDBSERVER_OPTIONS",
                buildGdbServerOptions(venv));
        launchConfig.setAttribute("org.eclipse.debug.core.ATTR_TARGET_PATH",
                artifact.absolutePath());
        launchConfig.setAttribute("org.eclipse.debug.core.REMOTE_TCP", "Local");
        launchConfig.setAttribute("org.eclipse.cdt.dsf.gdb.REMOTE_TCP", true);
        launchConfig.setAttribute("org.eclipse.cdt.dsf.gdb.PORT", DEFAULT_DEBUG_PORT);
        launchConfig.setAttribute(
                "org.eclipse.cdt.launch.remote.RemoteCDSFDebuggerTab.DEFAULTS_SET", true);
        launchConfig.setAttribute(
                "org.eclipse.cdt.dsf.gdb.internal.ui.launching.RemoteApplicationCDebuggerTab.DEFAULTS_SET",
                true);
        mapProject(launchConfig, project);
        launchConfig.doSave();
    }

    private void applyCommonAttributes(ILaunchConfigurationWorkingCopy launchConfig,
            IProject project, Venv venv, String kind) {
        launchConfig.setAttribute(ATTR_MANAGED, true);
        launchConfig.setAttribute(ATTR_KIND, kind);
        launchConfig.setAttribute(ATTR_VENV_PATH, venv.getPath());
        launchConfig.setAttribute(ATTR_VENV_NAME, getVenvName(venv));
        launchConfig.setAttribute(ATTR_PROJECT_PATH, project.getLocation().toOSString());
        launchConfig.setAttribute(ATTR_SCHEMA_VERSION, SCHEMA_VERSION);
    }

    private String buildRunArguments(String programPath, String sysroot) {
        if (sysroot != null && !sysroot.isBlank()) {
            return "-L " + quote(sysroot) + " " + quote(programPath);
        }
        return quote(programPath);
    }

    private String buildGdbServerOptions(Venv venv) {
        final var qemu =
                venv.getEmulatorDirPath() + File.separator + venv.getEmulatorExecutableName();
        final var qemuArg = escapeForDoubleQuotedScript(qemu);
        final var sysroot = venv.getSysroot();
        final var sysrootArg = sysroot != null && !sysroot.isBlank()
                ? " -L \"" + escapeForDoubleQuotedScript(sysroot) + "\""
                : "";
        // "Listening on port":
        // https://github.com/eclipse-cdt/cdt/blob/cdt_11_6/cross/org.eclipse.cdt.launch.remote/src/org/eclipse/cdt/launch/remote/launching/RemoteGdbLaunchDelegate.java#L145-L156
        final var script = String.join(";", new String[] {"set -eu", "port=\"${0#:}\"",
                "debuggee=\"$1\"", "shift", "echo \"mock: Listening on port $port\"",
                "exec \"" + qemuArg + "\"" + sysrootArg + " -g $port \"$debuggee\" \"$@\""});
        return String.format("-c '%s'", escapeForSingleQuotedScript(script));
    }

    private Optional<String> findGdbPath(Venv venv) {
        final var prefix = venv.getToolchainPrefix();
        if (prefix == null || prefix.isBlank()) {
            return Optional.empty();
        }
        final var toolchainPath = venv.getToolchainPath();
        if (toolchainPath == null || toolchainPath.isBlank()) {
            return Optional.empty();
        }
        try {
            final var path = Paths.get(toolchainPath, prefix + "-gdb");
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            return Optional.of(quote(path.toString()));
        } catch (InvalidPathException ignored) {
            return Optional.empty();
        }
    }

    private ArtifactInfo resolveArtifact(IProject project, Venv venv) {
        final var buildInfo = ManagedBuildManager.getBuildInfo(project);
        if (buildInfo == null || buildInfo.getManagedProject() == null) {
            return ArtifactInfo.empty();
        }

        IConfiguration config = null;
        final var projectPath = project.getLocation().toOSString();
        if (projectPath.equals(venv.getProjectPath())) {
            final var selected = buildInfo.getDefaultConfiguration();
            if (selected != null) {
                config = selected;
            }
        }
        if (config == null) {
            final var configs = buildInfo.getManagedProject().getConfigurations();
            for (final var candidate : configs) {
                if (candidate != null) {
                    config = candidate;
                    break;
                }
            }
        }
        if (config == null) {
            return ArtifactInfo.empty();
        }

        final var artifactName = Optional.ofNullable(config.getArtifactName()).orElse("");
        final var artifactExt = Optional.ofNullable(config.getArtifactExtension()).orElse("");
        final var normalizedArtifactName = normalizeArtifactName(artifactName, project.getName());
        if (normalizedArtifactName.isBlank()) {
            return ArtifactInfo.empty();
        }
        var fileName = normalizedArtifactName;
        if (!artifactExt.isBlank() && !normalizedArtifactName.endsWith("." + artifactExt)) {
            fileName = normalizedArtifactName + "." + artifactExt;
        }
        final var buildDir = Optional.ofNullable(config.getName()).orElse("Debug");
        final var absolute = project.getLocation().append(buildDir).append(fileName).toOSString();
        final var relative = buildDir + "/" + fileName;
        return new ArtifactInfo(relative, absolute, config.getId());
    }

    private String normalizeArtifactName(String artifactName, String projectName) {
        if (artifactName == null || artifactName.isBlank()) {
            return "";
        }
        return artifactName.replace("${ProjName}", projectName);
    }

    private ILaunchConfigurationWorkingCopy getOrCreate(String name, String typeId)
            throws CoreException {
        final var manager = DebugPlugin.getDefault().getLaunchManager();
        final var type = manager.getLaunchConfigurationType(typeId);
        final var existing = findLaunchConfigurationByName(manager.getLaunchConfigurations(), name);
        if (existing != null) {
            if (existing.getType() != null && typeId.equals(existing.getType().getIdentifier())) {
                return existing.getWorkingCopy();
            }
            throw new CoreException(Status.error(
                    "Launch configuration name already exists with a different type: " + name));
        }
        return type.newInstance(null, name);
    }

    private ILaunchConfiguration findLaunchConfigurationByName(ILaunchConfiguration[] configs,
            String name) {
        for (final var config : configs) {
            if (name.equals(config.getName())) {
                return config;
            }
        }
        return null;
    }

    private void mapProject(ILaunchConfigurationWorkingCopy launchConfig, IProject project) {
        launchConfig.setMappedResources(new IResource[] {project});
    }

    private String buildConfigName(IProject project, Venv venv, boolean run) {
        return String.format("%s (%s - RuyiSDK Venv %s)", project.getName(), run ? "Run" : "Debug",
                getVenvName(venv));
    }

    private String getVenvName(Venv venv) {
        return Path.of(venv.getPath()).getFileName().toString();
    }

    private String quote(String value) {
        return '"' + value.replace("\"", "\\\"") + '"';
    }

    /**
     * Escapes a complete script body so it can be safely placed inside single quotes
     * ({@code -c '...'}). Any single quote in the script is replaced with the idiom {@code '"'"'}
     * which ends the current single-quoted string, appends a double-quoted single-quote character,
     * and reopens the single-quoted string.
     */
    private String escapeForSingleQuotedScript(String input) {
        return input.replace("'", "'\"'\"'");
    }

    private String escapeForDoubleQuotedScript(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record ArtifactInfo(String relativePath, String absolutePath, String configId) {
        static ArtifactInfo empty() {
            return new ArtifactInfo("", "", "");
        }

        boolean isEmpty() {
            return relativePath.isEmpty() || absolutePath.isEmpty();
        }
    }
}
