@file:Suppress("TooGenericExceptionCaught")

package dev.xuexue.pi.coding

import dev.xuexue.pi.coding.exec.LocalPiCommandExecutor
import dev.xuexue.pi.coding.exec.PiCommandExecutor
import dev.xuexue.pi.coding.extensions.PiExtensionContext
import dev.xuexue.pi.coding.extensions.PiExtensionMode
import dev.xuexue.pi.coding.extensions.PiExtensionRunner
import dev.xuexue.pi.coding.extensions.PiModLoader
import dev.xuexue.pi.coding.extensions.PiPluginDiscovery
import dev.xuexue.pi.coding.extensions.PiPluginDiscoveryOptions
import dev.xuexue.pi.coding.extensions.PiPluginLoadResult
import dev.xuexue.pi.coding.extensions.PiPluginScope
import dev.xuexue.pi.coding.packages.DefaultPiPackageManager
import dev.xuexue.pi.coding.packages.PiPackageMissingAction
import dev.xuexue.pi.coding.packages.PiPackageResolution
import dev.xuexue.pi.coding.packages.PiPackageScope
import dev.xuexue.pi.coding.packages.PiPackageSource
import dev.xuexue.pi.coding.resources.DefaultResourceLoader
import dev.xuexue.pi.coding.resources.ResourceLoaderOptions
import dev.xuexue.pi.coding.settings.SettingsManager
import dev.xuexue.pi.coding.trust.ProjectTrustInteraction
import dev.xuexue.pi.coding.trust.ProjectTrustResolutionOptions
import dev.xuexue.pi.coding.trust.ProjectTrustStore
import dev.xuexue.pi.coding.trust.resolveProjectTrusted

data class PiRuntimeBootstrapOptions(
    val cwd: String,
    val agentDirectory: String,
    val trustOverride: Boolean? = null,
    val interaction: ProjectTrustInteraction? = null,
    val interactionPort: InteractionPort = NoOpInteractionPort,
    val mode: PiExtensionMode = PiExtensionMode.RPC,
    val commandExecutor: PiCommandExecutor = LocalPiCommandExecutor(),
    val onMissingPackage: suspend (PiPackageSource) -> PiPackageMissingAction = { PiPackageMissingAction.SKIP },
)

data class PiRuntimeBootstrapResult(
    val settingsManager: SettingsManager,
    val packageManager: DefaultPiPackageManager,
    val packageResolution: PiPackageResolution,
    val extensionRunner: PiExtensionRunner,
    val modLoader: PiModLoader,
    val resourceLoader: DefaultResourceLoader,
    val userPlugins: PiPluginLoadResult,
    val projectPlugins: PiPluginLoadResult,
)

/**
 * Creates the trusted Desktop runtime surface in Pi's security order.
 *
 * User plugins are activated first so they can answer `project_trust`. Project settings,
 * packages, extensions, and resources remain unread until trust succeeds.
 */
class PiRuntimeBootstrap(
    private val options: PiRuntimeBootstrapOptions,
) {
    suspend fun prepare(): PiRuntimeBootstrapResult? {
        val settingsManager =
            SettingsManager.create(
                cwd = options.cwd,
                agentDirectory = options.agentDirectory,
                projectTrusted = false,
            )
        val packageManager =
            DefaultPiPackageManager(
                cwd = options.cwd,
                agentDirectory = options.agentDirectory,
                settingsManager = settingsManager,
            )
        val userPackageResolution = packageManager.resolve(options.onMissingPackage)
        val userPlugins =
            PiPluginDiscovery(
                PiPluginDiscoveryOptions.fromSettings(
                    cwd = options.cwd,
                    agentDirectory = options.agentDirectory,
                    settingsManager = settingsManager,
                    projectTrusted = false,
                    additionalUserPaths =
                        userPackageResolution.extensionPaths
                            .filter { it.scope == PiPackageScope.USER }
                            .map { it.path },
                    scopesToLoad = setOf(PiPluginScope.USER),
                ),
            ).loadAll()
        val extensionRunner =
            PiExtensionRunner(
                cwd = options.cwd,
                mode = options.mode,
                commandExecutor = options.commandExecutor,
            )
        val modLoader =
            PiModLoader(
                cwd = options.cwd,
                mode = options.mode,
                commandExecutor = options.commandExecutor,
                projectTrusted = false,
                interaction = options.interactionPort,
            )
        return try {
            userPlugins.extensions.forEach { extension -> extensionRunner.load(extension) }
            userPlugins.mods.forEach { plugin ->
                modLoader.load(plugin.mod, plugin.packageInfo, trusted = true)
            }
            val trusted = resolveTrust(settingsManager, extensionRunner)
            if (!trusted) {
                extensionRunner.dispose()
                modLoader.reload()
                null
            } else {
                settingsManager.setProjectTrusted(true)
                finishBootstrap(settingsManager, extensionRunner, modLoader, userPlugins, packageManager)
            }
        } catch (error: Throwable) {
            extensionRunner.dispose()
            modLoader.reload()
            throw error
        }
    }

    private suspend fun resolveTrust(
        settingsManager: SettingsManager,
        extensionRunner: PiExtensionRunner,
    ): Boolean =
        resolveProjectTrusted(
            ProjectTrustResolutionOptions(
                cwd = options.cwd,
                trustStore = ProjectTrustStore.create(options.agentDirectory),
                trustOverride = options.trustOverride,
                defaultProjectTrust = settingsManager.getDefaultProjectTrust(),
                extensionRunner = extensionRunner,
                extensionContext =
                    PiExtensionContext(
                        cwd = options.cwd,
                        mode = options.mode,
                        isProjectTrusted = settingsManager::isProjectTrusted,
                        interaction = options.interactionPort,
                    ),
                interaction = options.interaction,
            ),
        )

    private suspend fun finishBootstrap(
        settingsManager: SettingsManager,
        extensionRunner: PiExtensionRunner,
        modLoader: PiModLoader,
        userPlugins: PiPluginLoadResult,
        packageManager: DefaultPiPackageManager,
    ): PiRuntimeBootstrapResult {
        val packageResolution = packageManager.resolve(options.onMissingPackage)
        val projectPlugins =
            PiPluginDiscovery(
                PiPluginDiscoveryOptions.fromSettings(
                    cwd = options.cwd,
                    agentDirectory = options.agentDirectory,
                    settingsManager = settingsManager,
                    projectTrusted = true,
                    additionalPaths =
                        packageResolution.extensionPaths
                            .filter { it.scope == PiPackageScope.TEMPORARY }
                            .map { it.path },
                    additionalProjectPaths =
                        packageResolution.extensionPaths
                            .filter { it.scope == PiPackageScope.PROJECT }
                            .map { it.path },
                    additionalUserPaths =
                        packageResolution.extensionPaths
                            .filter { it.scope == PiPackageScope.USER }
                            .map { it.path },
                    scopesToLoad = setOf(PiPluginScope.PROJECT, PiPluginScope.TEMPORARY),
                ),
            ).loadAll()
        projectPlugins.extensions.forEach { extension -> extensionRunner.load(extension) }
        projectPlugins.mods.forEach { plugin ->
            modLoader.load(plugin.mod, plugin.packageInfo, trusted = true)
        }
        val resourceLoader =
            DefaultResourceLoader(
                ResourceLoaderOptions(
                    cwd = options.cwd,
                    agentDirectory = options.agentDirectory,
                    settingsManager = settingsManager,
                ),
            )
        return PiRuntimeBootstrapResult(
            settingsManager = settingsManager,
            packageManager = packageManager,
            packageResolution = packageResolution,
            extensionRunner = extensionRunner,
            modLoader = modLoader,
            resourceLoader = resourceLoader,
            userPlugins = userPlugins,
            projectPlugins = projectPlugins,
        )
    }
}
