@file:Suppress("unused", "UnusedImport")

import org.gradle.api.publish.maven.MavenPom
import org.gradle.kotlin.dsl.assign


fun MavenPom.applyDefaultPomInfo() {
    url = "https://github.com/husker-dev/os-utils"

    licenses {
        license {
            name = "The Apache License, Version 2.0"
            url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
        }
    }
    developers {
        developer {
            id = "husker-dev"
            name = "Nikita Shtengauer"
            email = "shtengauer.nikita@gmail.com"
        }
    }
    scm {
        connection = "https://github.com/husker-dev/os-utils.git"
        developerConnection = "https://github.com/husker-dev/os-utils.git"
        url = "https://github.com/husker-dev/os-utils"
    }
}

class PluginStub