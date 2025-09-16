package com.mfc.recentaudiobuffer

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.mfc.recentaudiobuffer.speakeridentification.AppDatabase
import com.mfc.recentaudiobuffer.speakeridentification.DiarizationProcessor
import com.mfc.recentaudiobuffer.speakeridentification.SpeakerIdentifier
import com.mfc.recentaudiobuffer.speakeridentification.SpeakerRepository
import com.mfc.recentaudiobuffer.speakeridentification.Speaker
import com.mfc.recentaudiobuffer.speakeridentification.SpeakerClusteringConfig
import com.mfc.recentaudiobuffer.speakeridentification.SpeakerDao
import com.mfc.recentaudiobuffer.speakeridentification.SpeakerDiscoveryUiState
import com.mfc.recentaudiobuffer.speakeridentification.SpeakersViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import javax.inject.Inject

/**
 * Test class for the SpeakerRepository and its interaction with the Room database.
 * This ensures the fundamental database operations (Create, Read, Update, Delete) are working correctly.
 */
@RunWith(AndroidJUnit4::class)
class SpeakerRepositoryTest {

    @Inject
    lateinit var speakerDao: SpeakerDao

    @Inject
    lateinit var auth: FirebaseAuth

    @Inject
    lateinit var firestore: FirebaseFirestore

    @Inject
    lateinit var repository: SpeakerRepository

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        speakerDao = db.speakerDao()
        repository = SpeakerRepository(speakerDao, auth, firestore, context)
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    @Throws(Exception::class)
    fun writeSpeakerAndReadInList() = runBlocking {
        val speaker = Speaker(id = "1", name = "Alice", embedding = floatArrayOf(0.1f, 0.2f))
        repository.addSpeaker(speaker)
        val speakers = repository.getAllSpeakers().first()
        assertEquals("Alice", speakers[0].name)
    }

    @Test
    @Throws(Exception::class)
    fun updateSpeakerAndVerify() = runBlocking {
        val speaker = Speaker(id = "1", name = "Alice", embedding = floatArrayOf(0.1f))
        repository.addSpeaker(speaker)
        val updatedSpeaker = speaker.copy(name = "Alicia")
        repository.updateSpeaker(updatedSpeaker)
        val speakers = repository.getAllSpeakers().first()
        assertEquals(1, speakers.size)
        assertEquals("Alicia", speakers[0].name)
    }

    @Test
    @Throws(Exception::class)
    fun deleteSpeakerAndVerify() = runBlocking {
        val speaker1 = Speaker(id = "1", name = "Alice", embedding = floatArrayOf(0.1f))
        val speaker2 = Speaker(id = "2", name = "Bob", embedding = floatArrayOf(0.2f))
        repository.addSpeaker(speaker1)
        repository.addSpeaker(speaker2)
        repository.deleteSpeaker(speaker1)
        val speakers = repository.getAllSpeakers().first()
        assertEquals(1, speakers.size)
        assertEquals("Bob", speakers[0].name)
    }

    @Test
    @Throws(Exception::class)
    fun getSpeakersByIds() = runBlocking {
        val speaker1 = Speaker(id = "1", name = "Alice", embedding = floatArrayOf(0.1f))
        val speaker2 = Speaker(id = "2", name = "Bob", embedding = floatArrayOf(0.2f))
        val speaker3 = Speaker(id = "3", name = "Charlie", embedding = floatArrayOf(0.3f))
        repository.addSpeaker(speaker1)
        repository.addSpeaker(speaker2)
        repository.addSpeaker(speaker3)

        val result = repository.getSpeakersByIds(listOf("1", "3"))
        assertEquals(2, result.size)
        assertTrue(result.any { it.name == "Alice" })
        assertTrue(result.any { it.name == "Charlie" })
    }
}

/**
 * Test class for the Speaker Management UI flow using Espresso.
 * This test simulates a user navigating through the app to add a new speaker.
 * It verifies that the UI behaves as expected and that the data is persisted correctly.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SpeakerFlowTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Inject
    lateinit var speakerRepository: SpeakerRepository

    @Before
    fun init() {
        hiltRule.inject()
    }

    @Test
    fun addNewSpeaker_throughUI_isPersistedAndDisplayed() = runBlocking {
        // 1. Navigate from MainActivity to SpeakerManagementActivity
        onView(withContentDescription("Settings")).perform(click())
        onView(withText("Manage Enrolled Speakers")).perform(click())

        // 2. In SpeakerManagementActivity, click the FAB to add a new speaker
        onView(withText("Add Speaker")).perform(click())

        // 3. Type the new speaker's name in the dialog and save
        val newSpeakerName = "Diana"
        onView(withText("Speaker's Name")).perform(typeText(newSpeakerName))
        onView(withText("Save")).perform(click())

        // 4. Verify that the new speaker is now displayed on the screen
        onView(withText(newSpeakerName)).check(matches(isDisplayed()))

        // 5. Verify that the speaker was actually saved in the repository
        val speakers = speakerRepository.getAllSpeakers().first()
        assertTrue(speakers.any { it.name == newSpeakerName })
    }
}

/**
 * Test class for the EnrollmentViewModel.
 * This test uses a real audio file from the androidTest assets to verify the speaker enrollment logic.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EnrollmentViewModelTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var speakerRepository: SpeakerRepository

    @Inject
    lateinit var diarizationProcessor: DiarizationProcessor

    @Inject
    lateinit var speakerIdentifier: SpeakerIdentifier

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var workManager: WorkManager

    @Inject
    lateinit var speakerClusteringConfig: SpeakerClusteringConfig

    private lateinit var viewModel: SpeakersViewModel

    @Before
    fun init() {
        hiltRule.inject()
        val context = ApplicationProvider.getApplicationContext<Context>()
        viewModel = SpeakersViewModel(
            context,
            speakerRepository,
            speakerIdentifier,
            workManager,
            speakerClusteringConfig,
        )
    }

    @Test
    fun processAudioFile_andEnrollSpeaker_succeeds() = runBlocking {
        // 1. Load the test audio file from assets
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val assetManager = testContext.assets
        val inputStream = assetManager.open("oscar_talking_5min.wav")
        val fileBytes = inputStream.readBytes()
        val tempFile = createTempFile()
        tempFile.writeBytes(fileBytes)

        // 2. Start the processing
        viewModel.startScan("oscar_talking_5min.wav".toUri() as Set<Uri>)

        // 3. Wait for the processing to complete and verify the state
        val uiState =
            viewModel.uiState.first { it is SpeakerDiscoveryUiState.Success } as SpeakerDiscoveryUiState.Success
        assertTrue(uiState.unknownSpeakers.isNotEmpty())

        // 4. Enroll the first unknown speaker
        val unknownSpeaker = uiState.unknownSpeakers.first()
        val newSpeakerName = "Oscar"
        viewModel.addSpeaker(newSpeakerName, unknownSpeaker)

        // 5. Verify that the speaker is now in the database
        val speakers = speakerRepository.getAllSpeakers().first()
        assertTrue(speakers.any { it.name == newSpeakerName })
        assertNotNull(speakers.find { it.name == newSpeakerName }?.embedding)
    }
}
