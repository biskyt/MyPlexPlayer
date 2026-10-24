package com.biskyt.myplexplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.biskyt.myplexplayer.testutil.TestApp
import com.biskyt.myplexplayer.testutil.createUiComposeRule
import com.biskyt.myplexplayer.ui.common.TvTextField
import com.biskyt.myplexplayer.ui.theme.MyPlexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class TextFieldTest {
    @get:Rule val compose = createUiComposeRule()

    @Test
    fun `next key opens the following field and text is shown as typed`() {
        compose.setContent {
            MyPlexTheme {
                var user by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                val passwordEdit = remember { mutableStateOf(false) }
                Column {
                    TvTextField(user, { user = it }, "Username", autoCorrect = false, onNext = { passwordEdit.value = true })
                    TvTextField(password, { password = it }, "Password", autoCorrect = false, editing = passwordEdit)
                }
            }
        }
        compose.onNode(hasText("Username")).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(hasSetTextAction()).performTextInput("me")
        compose.onNode(hasSetTextAction()).performImeAction()

        // The password box is now open and focused, with no need to select it again.
        compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("Pa55!word")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNode(hasText("Pa55!word")).assertExists()
        compose.onNode(hasText("me")).assertExists()
    }
}
