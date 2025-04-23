package ru.miku.annotation.serialization.plugin

import groovy.io.FileType
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.*

import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

class AnnotationPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.extensions.create('serializationInjectorConfiguration', AnnotationPluginExtension)

        project.afterEvaluate {
            project.tasks.register('injectSerialization', AddSerializableAnnotationTask) {
                group = 'proto'
                description = 'Adds @Serializable annotation to Kotlin data classes'

                def config = project.serializationInjectorConfiguration
                inputDirectory.set(project.file(config.input ?: "${project.buildDir}/classes/kotlin/main"))
                outputDirectory.set(project.file(config.input ?: "${project.buildDir}/classes-annotated/main"))
                serializerPackage = config.serializers
            }

            project.tasks.named('injectSerialization').configure {
                dependsOn 'generateProto'
            }

            project.tasks.named('compileKotlin').configure {
                dependsOn 'injectSerialization'
            }
        }
    }
}

class AnnotationPluginExtension {
    String input
    String serializers
}

class AddSerializableAnnotationTask extends DefaultTask {
    @InputDirectory
    final DirectoryProperty inputDirectory = project.objects.directoryProperty()

    @OutputDirectory
    final DirectoryProperty outputDirectory = project.objects.directoryProperty()

    @Input
    String serializerPackage

    @TaskAction
    void execute() {
        def serializers = [:]
        Files.walkFileTree(
                Paths.get(project.projectDir.toString(), 'src/main/kotlin', serializerPackage.replace('.', '/')),
                new FileVisitor<Path>() {
                    @Override
                    FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        return FileVisitResult.CONTINUE
                    }

                    @Override
                    FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.toFile().name.toLowerCase().endsWith("kt")) {
                            serializers.putAll(getTypeToGenericRelation(file.text, "KSerializer"))
                        }
                        return FileVisitResult.CONTINUE
                    }

                    @Override
                    FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                        return FileVisitResult.CONTINUE
                    }

                    @Override
                    FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        return FileVisitResult.CONTINUE
                    }
                }
        )

        println("Found serializers: ")
        serializers.each {
            println("\tType: '${it.key}', class: '${it.value}'")
        }

        inputDirectory.get().asFile.eachFileRecurse(FileType.FILES) { File file ->
            if (file.name.endsWith('.kt')) {
                doProcessClass(file, serializers as Map<String, String>)
            }
        }
    }

    void doProcessClass(File file, Map<String, String> serializers) {
        if (file.text.contains("data class")) {
            def text = file.readLines()
            def counter = text.findIndexOf {
                it.contains("data class")
            } - 1
            if (counter >= 0) {
                text[counter] = text[counter] + "\n@kotlinx.serialization.Serializable\n"
            }
            def altered = text.collect {
                String result
                if (it.contains("val ")) {
                    def regex = it.contains("?") ? /:\s*([\w?<>]+)/ : /:\s*(\w+)/
                    def matcher = (it =~ regex)
                    if (matcher.find()) {
                        def type = matcher.group(1).replace("?", "").strip()
                        if (!serializers.containsKey(type)) {
                            result = it
                        } else {
                            println "found serializer: ${serializers.get(type)}"
                            result = "@kotlinx.serialization.Serializable(with = ${serializers.get(type)}::class)\n$it"
                        }
                    } else throw new IllegalStateException("Unparsable prop string: $it")
                } else result = it
                return result
            }
            file.write("")
            altered.each {
                file.append("$it\n")
            }
        }
    }

    def getTypeToGenericRelation(String sourceCode, String targetInterfaceName) {
        def map = [:]
        Disposable disposable = Disposer.newDisposable()
        def configuration = new CompilerConfiguration()
        def environment = KotlinCoreEnvironment.createForProduction(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
        def psiFileFactory = PsiFileFactory.getInstance(environment.project)
        def ktFile = psiFileFactory.createFileFromText("MyFile.kt", KotlinLanguage.INSTANCE, sourceCode) as KtFile
        ktFile.declarations.findAll { it instanceof KtClassOrObject }.each { KtClassOrObject klass ->
            klass.getSuperTypeListEntries().each { entry ->
                def typeRef = entry.typeReference
                def typeElement = typeRef?.typeElement
                if (typeElement instanceof KtUserType) {
                    def referencedName = typeElement.referencedName
                    if (referencedName == targetInterfaceName && typeElement.typeArgumentList != null) {
                        def typeArgs = typeElement.typeArgumentList.arguments
                        map.put(typeArgs[0].text.toString().strip(), klass.classId.asFqNameString())
                    }
                }
            }
        }
        Disposer.dispose(disposable)
        return map
    }
}