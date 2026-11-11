// ZOUKAN -->
// The parts of the Android app's E-Hentai code the desktop app does not compile (the login
// activity, the update worker, the gallery adder), as far as EHentai.kt reads them.
package exh.ui.login

/** E-Hentai's sign-in cookies; desktop signs in through [zoukan.desktop.exh.EhLogin]. */
object EhLoginActivity {
    const val MEMBER_ID_COOKIE = "ipb_member_id"
    const val PASS_HASH_COOKIE = "ipb_pass_hash"
    const val IGNEOUS_COOKIE = "igneous"
}
// ZOUKAN <--
