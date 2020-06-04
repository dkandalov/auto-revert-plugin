package limitedwip.watchdog

import com.intellij.diff.comparison.ComparisonManagerImpl
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import limitedwip.shouldEqual
import limitedwip.watchdog.components.calculateChangeSizeInLines
import org.junit.Test

class CalculateChangeSizeTests : BasePlatformTestCase() {
    private val comparisonManager = ComparisonManagerImpl()

    @Test fun `test trivial diffs`() {
        changeSizeInLines("", "") shouldEqual ChangeSize(0)
        changeSizeInLines("text", "") shouldEqual ChangeSize(1)
        changeSizeInLines("", "text") shouldEqual ChangeSize(1)
        changeSizeInLines("text", "text") shouldEqual ChangeSize(0)
    }

    @Test fun `test change size calculation`() {
        changeSizeInLines("text\ntext", "text") shouldEqual ChangeSize(1)
        changeSizeInLines("text\ntext", "") shouldEqual ChangeSize(2)
    }

    @Test fun `test ignore spaces and newlines`() {
        changeSizeInLines("\n\ntext\n\n", "") shouldEqual ChangeSize(1)
        changeSizeInLines("    text    ", "") shouldEqual ChangeSize(1)
        changeSizeInLines("text\n\n\ntext\n\n\n", "text") shouldEqual ChangeSize(1)
        changeSizeInLines("text\n\n\ntext\n\n\n", "\n") shouldEqual ChangeSize(2)
    }

    @Test fun `test correctly diff a bit of code`() {
        changeSizeInLines(
            """
            |try {
            |} catch (Error error) {
            |}
            """.trimMargin(),
            """
            |try {
            |}
            """.trimMargin()
        ) shouldEqual ChangeSize(1)
    }

    private fun changeSizeInLines(before: String, after: String) =
        comparisonManager.calculateChangeSizeInLines(Change(
            SimpleContentRevision(before, LocalFilePath("before.txt", false), "some-revision-before"),
            SimpleContentRevision(after, LocalFilePath("after.txt", false), "some-revision-after")
        ))
}