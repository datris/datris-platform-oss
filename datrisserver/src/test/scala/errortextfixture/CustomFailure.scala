package errortextfixture

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** An exception outside every package root ErrorText.classChain shows fully
  * qualified (ErrorTextSpec). */
class CustomFailure(message: String) extends RuntimeException(message)
