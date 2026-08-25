package brenay.lib

import org.scalatest.{BeforeAndAfterEachTestData, TestData}

/** Trait to provide a unique workspace name for a spinalHDL simulation.
  * Must be mixed into a class that extends Suite (like AnyFunSuite).
  *
  * Add this `SimConfig.workspaceName(classAndTestName)` so it is now in a
  * unique directory.
  */
trait SimWorkspaceNaming extends BeforeAndAfterEachTestData {
  self: org.scalatest.Suite =>

  private val classAndTestNameThreadLocal = new ThreadLocal[String]

  override def beforeEach(testData: TestData): Unit = {
    classAndTestNameThreadLocal.set(
      getClass().getCanonicalName() + "-" +
        testData.name.replaceAll("[^a-zA-Z0-9._-]", "_")
    )
    super.beforeEach(testData)
  }

  /** Return a string with canonical class name of the suite and name of the test, path friendly */
  def classAndTestName: String = {
    val result = classAndTestNameThreadLocal.get()
    if (result == null) {
      throw new RuntimeException(
        "classAndTestName was accessed without being set. " +
          "Make sure this is called within a test method (after beforeEach). " +
          s"Class: ${getClass.getCanonicalName}\n" +
          "Also be sure it's in the same thread (it use ThreadLocal). If it's not the case," +
          "use a capture before moving to the new thread for example."
      )
    }
    result
  }

  // Return the command to open surfer with current test wave.fst.
  def surferCommandString(): String = {
    val className = getClass().getCanonicalName().split("\\.")
    val confFilename =
      s"src/test/scala/${className.dropRight(1).mkString(" / ")}/${className.last}-surfer.conf"

    "surfer" +
      s" simWorkspace/${classAndTestName}/test/wave.fst" +
      s" -s $confFilename"
  }
}
