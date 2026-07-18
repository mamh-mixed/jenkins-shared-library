package com.daluobai.jenkinslib.utils

import com.daluobai.jenkinslib.constant.EFileReadType
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

class FileUtilsTest {

    @Test
    void hostPathUsesCurrentJenkinsNodeSteps() {
        FakeSteps steps = new FakeSteps()

        String content = new FileUtils(steps).readString(EFileReadType.HOST_PATH, '/agent/config.json')

        assertEquals('{"ok":true}', content)
        assertEquals('/agent/config.json', steps.checkedPath)
        assertEquals('/agent/config.json', steps.readArgs.file)
    }

    static class FakeSteps {
        String checkedPath
        Map readArgs

        boolean fileExists(String path) {
            checkedPath = path
            return true
        }

        String readFile(Map args) {
            readArgs = args
            return '{"ok":true}'
        }
    }
}
