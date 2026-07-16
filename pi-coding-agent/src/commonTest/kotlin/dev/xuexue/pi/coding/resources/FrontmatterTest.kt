package dev.xuexue.pi.coding.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrontmatterTest {
    @Test
    fun parsesYamlBlockDescriptionsForSkills() {
        val parsed =
            parseFrontmatter(
                """
                ---
                name: multi
                description: |
                  First line.
                  Second line.
                ---
                Skill body
                """.trimIndent(),
            )

        assertEquals("First line.\nSecond line.", parsed.fields.string("description"))
        assertEquals("Skill body", parsed.body)
    }

    @Test
    fun rejectsUnclosedYamlCollectionLikePi() {
        assertFailsWith<IllegalArgumentException> {
            parseFrontmatter("---\ndescription: [unclosed bracket\n---\nbody")
        }
    }
}
