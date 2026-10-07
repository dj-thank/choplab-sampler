package com.choplab.jvm

/** Recovery refused to replace existing, unreadable production files with an empty project. */
class AutosaveRecoveryException : IllegalStateException("Autosave recovery failed; existing files were preserved")
