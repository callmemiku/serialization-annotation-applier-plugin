package ru.miku.annotation.serialization.plugin

import groovy.io.FileType
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

class AnnotationPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.extensions.create('serializationInjectorConfiguration', AnnotationPluginExtension)
        
        project.afterEvaluate {
            project.tasks.register('injectSerialization', AddSerializableAnnotationTask) {
                group = 'proto'
                description = 'Adds @Serializable annotation to Kotlin data classes'
                
                def config = project.serializationInjectorConfiguration
                inputDirectory.set(project.file(config.inputPath ?: "${project.buildDir}/classes/kotlin/main"))
                outputDirectory.set(project.file(config.inputPath ?: "${project.buildDir}/classes-annotated/main"))
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
    String inputPath
}

class AddSerializableAnnotationTask extends DefaultTask {
    @InputDirectory
    final DirectoryProperty inputDirectory = project.objects.directoryProperty()
    
    @OutputDirectory
    final DirectoryProperty outputDirectory = project.objects.directoryProperty()
    
    @TaskAction
    void execute() {
        inputDirectory.get().asFile.eachFileRecurse(FileType.FILES) { File file ->
            if (file.name.endsWith('.kt')) {
                doProcessClass(file)
            }
        }
    }
    
    void doProcessClass(File file) {
        logger.info("Checking: ${file.name}")
        def text = file.readLines()
        def counter = text.findIndexOf {
            it.contains("data class")
        } - 1
        if (counter >= 0) {
            text[counter] = text[counter] + "\n@Serializable\n"
            text[1] = text[1] + "\nimport kotlinx.serialization.Serializable\n"
        }
        file.write("")
        text.each {
            file.append("$it\n")
        }
    }
}