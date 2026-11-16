package zoukan.desktop.secrets

/** The OS secret store could not be read (access denied, locked keychain); no key was replaced. */
class SecretStoreUnavailableException(val storeName: String, message: String, cause: Throwable) : Exception(message, cause)
