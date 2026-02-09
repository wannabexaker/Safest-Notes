package com.tezas.safestnotes

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.contrib.DrawerActions
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers.*
import com.tezas.safestnotes.data.Folder
import com.tezas.safestnotes.data.Note
import com.tezas.safestnotes.data.NotesDatabase
import com.tezas.safestnotes.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.hamcrest.Matchers.allOf
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.ViewAction
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.UiController

@RunWith(AndroidJUnit4::class)
class DrawerAndSelectionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("safest_notes_db")
    }

    @Test
    fun drawer_expandCollapse_showsChildFoldersOnly() {
        seedFolders()
        ActivityScenario.launch(MainActivity::class.java)

        onView(withId(R.id.drawer_layout)).perform(DrawerActions.open())

        onView(withText("Child Folder")).check(doesNotExist())

        onView(withId(R.id.drawer_folders_recycler))
            .perform(
                RecyclerViewActions.actionOnItem<RecyclerView.ViewHolder>(
                    hasDescendant(withText("Parent Folder")),
                    clickChildViewWithId(R.id.folder_chevron)
                )
            )

        onView(withText("Child Folder")).check(matches(isDisplayed()))
    }

    @Test
    fun longPress_entersSelectionMode() {
        seedNotes()
        ActivityScenario.launch(MainActivity::class.java)

        onView(withId(R.id.recyclerView))
            .perform(
                RecyclerViewActions.actionOnItemAtPosition<RecyclerView.ViewHolder>(
                    0,
                    longClick()
                )
            )

        onView(withText("Delete")).check(matches(isDisplayed()))
    }

    private fun seedFolders() = runBlocking {
        withContext(Dispatchers.IO) {
            val db = NotesDatabase.getDatabase(context)
            db.folderDao().insert(Folder(name = "Parent Folder"))
            val parent = db.folderDao().getFolderByName("Parent Folder")!!
            db.folderDao().insert(Folder(name = "Child Folder", parentFolderId = parent.id))
        }
    }

    private fun seedNotes() = runBlocking {
        withContext(Dispatchers.IO) {
            val db = NotesDatabase.getDatabase(context)
            db.noteDao().insert(Note(title = "Note A", content = "Test", timestamp = System.currentTimeMillis()))
        }
    }

    private fun clickChildViewWithId(id: Int): ViewAction {
        return object : ViewAction {
            override fun getConstraints() = null
            override fun getDescription() = "Click on a child view with specified id."
            override fun perform(uiController: UiController, view: View) {
                val v = view.findViewById<View>(id)
                v.performClick()
            }
        }
    }
}
