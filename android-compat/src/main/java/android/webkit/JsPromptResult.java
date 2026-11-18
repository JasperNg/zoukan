/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.webkit;

// ZOUKAN -->
// Android's JsPromptResult, in place of android.jar's stub (whose methods throw), so the WebView can
// hand a page's prompt to an extension's WebChromeClient
// ZOUKAN <--

/**
 * Public class for handling JavaScript prompt requests. The WebChromeClient will receive a
 * {@link WebChromeClient#onJsPrompt(WebView, String, String, String, JsPromptResult)} call with a
 * JsPromptResult instance as a parameter. This object is used to return the result of this user
 * action.
 */
public class JsPromptResult extends JsResult {
    // String result of the prompt
    private String mStringResult;

    /**
     * Handle a confirmation response from the user.
     */
    public void confirm(String result) {
        mStringResult = result;
        confirm();
    }

    /**
     * @hide Only for use by WebViewProvider implementations
     */
    public JsPromptResult(ResultReceiver receiver) {
        super(receiver);
    }

    /**
     * @hide Only for use by WebViewProvider implementations
     */
    public String getStringResult() {
        return mStringResult;
    }
}
