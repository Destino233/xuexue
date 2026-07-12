package dev.xuexue.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkbenchUiModelTest {
    @Test
    fun widthClassesHaveStableResponsiveBoundaries() {
        assertEquals(WorkbenchWidthClass.COMPACT, workbenchWidthClass(899f))
        assertEquals(WorkbenchWidthClass.MEDIUM, workbenchWidthClass(900f))
        assertEquals(WorkbenchWidthClass.MEDIUM, workbenchWidthClass(1_199f))
        assertEquals(WorkbenchWidthClass.EXPANDED, workbenchWidthClass(1_200f))
    }

    @Test
    fun compactNavigationStartsAtUsefulContent() {
        assertEquals(CompactWorkbenchPage.FILES, initialCompactPage(hasActiveDocument = false))
        assertEquals(CompactWorkbenchPage.DOCUMENT, initialCompactPage(hasActiveDocument = true))
        assertEquals(ToolWindow.OUTLINE, CompactWorkbenchPage.OUTLINE.toolWindow)
        assertEquals(null, CompactWorkbenchPage.AGENT.toolWindow)
    }

    @Test
    fun mediumLayoutNeverSelectsTwoAuxiliaryPanels() {
        assertEquals(WorkbenchAuxiliaryPanel.AGENT, mediumAuxiliaryPanel(toolWindowVisible = true, agentVisible = true))
        assertEquals(WorkbenchAuxiliaryPanel.TOOL_WINDOW, mediumAuxiliaryPanel(toolWindowVisible = true, agentVisible = false))
        assertEquals(WorkbenchAuxiliaryPanel.NONE, mediumAuxiliaryPanel(toolWindowVisible = false, agentVisible = false))
    }
}
