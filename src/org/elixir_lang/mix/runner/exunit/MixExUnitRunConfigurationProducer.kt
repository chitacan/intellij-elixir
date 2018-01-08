package org.elixir_lang.mix.runner.exunit

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.ide.projectView.impl.ProjectRootsUtil
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Ref
import com.intellij.psi.*
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.sdk.elixir.Type
import org.elixir_lang.sdk.elixir.Type.mostSpecificSdk
import java.io.File

private const val UNKNOWN_LINE = -1

private fun configurationName(file: PsiFileSystemItem,
                              workingDirectory: String?,
                              basePath: String?): String {
    val filePath = file.virtualFile.path
    val suffix = if (workingDirectory != null) {
        val prefix = workingDirectory + File.separator
        val relativeFilePath = filePath.removePrefix(prefix)

        if (basePath != null && workingDirectory != basePath && workingDirectory.startsWith(basePath)) {
            val otpAppName = File(workingDirectory).name

            "$otpAppName $relativeFilePath"
        } else {
            relativeFilePath
        }
    } else {
        file.name
    }

    return "Mix ExUnit " + suffix
}

private fun configurationName(file: PsiFileSystemItem,
                              lineNumber: Int,
                              workingDirectory: String?,
                              basePath: String?): String =
        if (lineNumber == UNKNOWN_LINE) {
            configurationName(file, workingDirectory, basePath)
        } else {
            "${configurationName(file, workingDirectory, basePath)}:$lineNumber"
        }

private fun isConfigurationFromContextImpl(configuration: MixExUnitRunConfiguration, psiElement: PsiElement): Boolean {
    val contextConfiguration = MixExUnitRunConfiguration(configuration.name, configuration.project)

    return setupConfigurationFromContextImpl(contextConfiguration, psiElement) &&
            contextConfiguration.programParameters == configuration.programParameters &&
            contextConfiguration.workingDirectory == configuration.workingDirectory
}

private fun lineNumber(psiElement: PsiElement): Int {
    val containingFile = psiElement.containingFile
    val documentLineNumber = PsiDocumentManager
            .getInstance(containingFile.project)
            .getDocument(containingFile)
            ?.getLineNumber(psiElement.textOffset)
            ?:
            0

    return if (documentLineNumber == 0) {
        UNKNOWN_LINE
    } else {
        documentLineNumber + 1
    }
}

private fun programParameters(item: PsiFileSystemItem, workingDirectory: String?): String =
        programParameters(item, UNKNOWN_LINE, workingDirectory)

private fun programParameters(item: PsiFileSystemItem,
                              lineNumber: Int,
                              workingDirectory: String?): String {
    val path = item.virtualFile.path
    val relativePath = if (workingDirectory != null) {
        path.removePrefix(workingDirectory + File.separator)
    } else {
        path
    }

    return if (lineNumber != UNKNOWN_LINE) {
        "$relativePath:$lineNumber"
    } else {
        relativePath
    }
}

private fun setupConfigurationFromContextImpl(configuration: MixExUnitRunConfiguration,
                                              psiElement: PsiElement): Boolean =
        when (psiElement) {
            is PsiDirectory -> {
                val module = ModuleUtilCore.findModuleForPsiElement(psiElement)
                val sdk = if (module != null) {
                    mostSpecificSdk(module)
                } else {
                    val projectRootManager = ProjectRootManager.getInstance(psiElement.project)
                    projectRootManager.projectSdk
                }
                val sdkTypeId = sdk?.sdkType

                if ((sdkTypeId == null || sdkTypeId == Type.getInstance()) &&
                        ProjectRootsUtil.isInTestSource(psiElement.virtualFile, psiElement.project)) {
                    val basePath = psiElement.getProject().basePath
                    val workingDirectory = workingDirectory(psiElement, basePath)

                    configuration.workingDirectory = workingDirectory
                    configuration.name = configurationName(psiElement, workingDirectory, basePath)
                    configuration.programParameters = programParameters(psiElement, workingDirectory)

                    true
                } else {
                    false
                }
            }
            else -> {
                val containingFile = psiElement.containingFile

                when {
                    !(containingFile is ElixirFile || containingFile is PsiDirectory) ->
                        false
                    ProjectRootsUtil.isInTestSource(containingFile) -> {
                        val basePath = psiElement.project.basePath
                        val workingDirectory = workingDirectory(psiElement, basePath)
                        val lineNumber = lineNumber(psiElement)

                        configuration.workingDirectory = workingDirectory
                        configuration.name = configurationName(containingFile, lineNumber, workingDirectory, basePath)
                        configuration.programParameters = programParameters(containingFile, lineNumber, workingDirectory)

                        true
                    }
                    else ->
                        false
                }
            }
        }

private fun workingDirectory(directory: PsiDirectory, basePath: String?): String? =
        if (directory.findFile("mix.exs") != null) {
            directory.virtualFile.path
        } else {
            directory.parent?.let { workingDirectory(it, basePath) } ?: basePath
        }

private fun workingDirectory(element: PsiElement, basePath: String?): String? =
        when (element) {
            is PsiDirectory -> workingDirectory(element, basePath)
            is PsiFile -> workingDirectory(element, basePath)
            else -> workingDirectory(element.containingFile, basePath)
        }

private fun workingDirectory(file: PsiFile, basePath: String?): String? =
        workingDirectory(file.containingDirectory, basePath)

class MixExUnitRunConfigurationProducer:
        RunConfigurationProducer<MixExUnitRunConfiguration>(MixExUnitRunConfigurationType.getInstance()) {
    override fun setupConfigurationFromContext(runConfig: MixExUnitRunConfiguration,
                                               context: ConfigurationContext,
                                               ref: Ref<PsiElement>): Boolean =
            ref.get()?.let { it.isValid && setupConfigurationFromContextImpl(runConfig, it) } == true

    override fun isConfigurationFromContext(runConfig: MixExUnitRunConfiguration,
                                            context: ConfigurationContext): Boolean =
            context.psiLocation?.let {
                it.isValid && isConfigurationFromContextImpl(runConfig, it)
            } == true
}