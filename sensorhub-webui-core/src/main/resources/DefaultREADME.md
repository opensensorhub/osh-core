## Missing README

A README file could not be found for this module.

If this is a mistake, please be sure that...

*   The module contains a file titled `README.md` in its root directory.
*   Your node's `build.gradle` file (the outermost one) includes the following:
    *   ```plaintext
        allprojects {
            version = oshCoreVersion
            tasks.register('copyReadme', Copy) {
                from "${projectDir}/README.md"
                into "${buildDir}/resources/main"
                onlyIf { file("${projectDir}/README.md").exists() }
            }
        }
        
        subprojects {
            // inject all repositories from included builds if any
            repositories.addAll(rootProject.repositories)
            plugins.withType(JavaPlugin) {
                processResources {
                    dependsOn copyReadme
                }
            }
        }
        ```