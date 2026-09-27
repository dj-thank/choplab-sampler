package com.choplab.ui.separation

import com.choplab.core.separation.FourStemPort

/** Creates a dialog-owned worker. Opening must not download, decode or start inference. */
fun interface FourStemFactory { fun create(): FourStemPort }
