package com.daluobai.jenkinslib.resources

import com.daluobai.jenkinslib.utils.TemplateUtils
import org.junit.jupiter.api.Test

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class ServiceByPidTemplateTest {

    @Test
    void renderedScriptDerivesServiceDirectoryFromScriptPath() {
        String template = Files.readString(
                Path.of('resources/template/shell/javaWeb/serviceByPID.sh'),
                StandardCharsets.UTF_8
        )
        String rendered = TemplateUtils.makeTemplate(template, [
                appName   : 'demo',
                javaPath  : '/usr/bin/java',
                runOptions: '',
                archiveName: 'app.jar',
                runArgs   : ''
        ])

        assertTrue(rendered.contains('SHELL_DIR=$(dirname "$SHELL_PATH")'))
        assertFalse(rendered.contains('SHELL_DIR=$(dirname "$path")'))
    }
}
