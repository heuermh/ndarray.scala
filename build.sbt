import org.scalajs.jsenv.nodejs.NodeJSEnv
import scalajs._

default(
  group("org.lasersonlab"),
  versions(
    hammerlab.          bytes → "1.3.0",
    hammerlab.        channel → "1.5.3",
    hammerlab.       cli.base → "1.0.1",
    hammerlab.     math.utils → "2.4.0".snapshot,
    hammerlab.          paths → "1.6.0",
    hammerlab.          types → "1.5.0".snapshot,
    hammerlab.shapeless_utils → "1.5.1",
    hammerlab.             io → "5.2.1",

    diode.react → "1.1.4.131",
    dom → "0.9.6"
  ),
  circe.version := "0.11.1",
  http4s.version := "0.20.0-M1",
  diode.version := "1.1.4",
)

lazy val blosc =
  cross
    .settings(
      dep(math.utils)
    )
lazy val `blosc-x` = blosc.x

lazy val cloud =
  cross
    .in(new File("cloud"))
    .settings(
      subgroup("cloud", "all")
    )
    .dependsOn(aws, gcp)
    .aggregate(aws, gcp)
lazy val `cloud-x` = cloud.x

lazy val aws =
  cross
    .in(new File("cloud") / "aws")
    .settings(
      subgroup("cloud")
    )
    .jvmSettings(
      dep(
        "org.lasersonlab" ^ "s3fs" ^ "2.2.3"
      )
    )
lazy val `aws-x` = aws.x

lazy val gcp =
  cross
    .in(new File("cloud") / "gcp")
    .settings(
      subgroup("cloud"),
      dep(
        cats,
        circe,
        circe.generic,

        hammerlab.types,

        shapeless,
        sttp
      ),
      enableMacroParadise
    )
    .jsSettings(
      dep(
        dom
      )
    )
    .jvmSettings(
      dep(
        "org.lasersonlab" ^ "google-cloud-nio" ^ "0.55.2-alpha",
      )
    )
    .dependsOn(
      `circe-utils`,
       uri
    )
lazy val `gcp-x` = gcp.x

lazy val `circe-utils` =
  cross
    .in(new File("circe"))
    .settings(
      dep(
        circe,
        circe.generic,
        hammerlab.shapeless_utils % "1.5.1",
        shapeless
      ),
      buildInfoPackage := "org.lasersonlab.circe_utils"  // TODO: do this snake-casing automatically in plugin
    )
lazy val `circe-utils-x` = `circe-utils`.x

lazy val convert =
  project
    .settings(
      dep(
        hammerlab.cli.base,
        hammerlab.io,
        hammerlab.paths,
      ),
      // Test-resources include "hidden" (basenames starting with ".") Zarr-metadata files that we need to include on the
      // test classpath for tests to be able to read them
      excludeFilter in sbt.Test := NothingFilter,
      partialUnification
    )
    .dependsOn(
      cloud.jvm,
      netcdf,
      zarr.jvm andTest
    )

lazy val ndarray =
  cross
    .settings(
      dep(
        cats,
        hammerlab.shapeless_utils,
        hammerlab.types,
        shapeless
      ),
      kindProjector.settings,
      partialUnification
    )
    .dependsOn(
      slist
    )
lazy val `ndarray-x` = ndarray.x

lazy val netcdf = project.settings(
  dep(
    hammerlab.bytes,
    hammerlab.cli.base,
    hammerlab.io,
    hammerlab.paths,
    hammerlab.types,
  )
).dependsOn(
  cloud.jvm,
  utils
)

lazy val singlecell = project.settings(
  spark,
  spark.version := "2.2.1",
  dep(
    spark.mllib,
    spark.sql
  )
).dependsOn(
  utils
)

lazy val slist = cross.settings(
  dep(
    cats,
    hammerlab.types
  )
)
lazy val `slist-x` = slist.x

lazy val testing =
  cross
    .settings(
      dep(
        cats,
        "com.lihaoyi" ^^ "utest" ^ "0.6.6",
        hammerlab.test.suite,
        magnolia
      )
    )
    .dependsOn(
      uri
    )
lazy val `testing-x` = testing.x

lazy val uri =
  cross
    .settings(
      dep(
        cats,
        cats.effect,

        circe,
        circe.generic,
        circe.lib("generic-extras"),  // TODO: alias
        circe.parser,

        fs2,

        hammerlab.bytes,
        hammerlab.types,
        hammerlab.math.utils,

        sourcecode,
        sttp,

        "io.github.cquiroz" ^^ "scala-java-time" ^ "2.0.0-M13",
        "com.lihaoyi" ^^ "utest" ^ "0.6.6" tests
      ),
      enableMacroParadise,
      testFrameworks += new TestFramework("utest.runner.Framework")
    )
    .jvmSettings(
      http4s.version := "0.19.0",
      dep(
        // TODO: alias these
        "com.typesafe.akka" ^^ "akka-actor" ^ "2.5.19",
        "com.typesafe.akka" ^^ "akka-stream" ^ "2.5.19",
        "com.typesafe.akka" ^^ "akka-http" ^ "10.1.7",
        "com.typesafe.akka" ^^ "akka-http-core" ^ "10.1.7",
        commons.io,
        fs2.io,
        http4s. dsl,
        http4s.`blaze-client`,
        "biz.enef" ^^ "slogging-slf4j" ^ "0.6.1",
        "org.slf4j" ^ "slf4j-simple" ^ "1.7.25"
      )
    )
    .jsSettings(
      //jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(),
      scalaJSUseMainModuleInitializer := true,
      dep(
        "biz.enef" ^^ "slogging" ^ "0.6.1",
        dom,
        "io.scalajs.npm" ^^ "request" ^ "0.4.2"
      ),
    )
lazy val `uri-x` = uri.x

lazy val utils = project.settings(
  crossPaths := false,
  dep(
    hammerlab.channel,

    "org.lasersonlab.thredds" ^ "cdm" ^ "5.0.0",
    "com.novocode" ^ "junit-interface" ^ "0.11" tests
  )
)

// Stubs for a "viewer" webapp (client+server)
lazy val viewerCommon =
  cross
    .settings(
      dep(
        scalatags,
        circe,
        circe.generic,
        circe.parser
      )
    )

lazy val viewerClient =
  project
    .settings(

      slinky.version := "0.5.1",
      version in startWebpackDevServer := "3.1.14",

      react,
      dep(

        diode,
        diode.react,
        dom,

        cats,
        circe,
        circe.generic,
        circe.parser,
        sttp,
        hammerlab.types
      ),
      webpackBundlingMode := BundlingMode.LibraryAndApplication(),
      enableMacroParadise,
      kindProjector,
      partialUnification,
      scalaJSUseMainModuleInitializer := true,
      slinky,

      testFrameworks := Seq(new TestFramework("utest.runner.Framework")),

      npmDependencies in Compile ++=
        Seq(
          "pako"        → "1.0.7",
          "react-proxy" → "1.1.8"
        ),
//      scalacOptions += "-Xlog-implicits"
    )
    .enablePlugins(JS, ScalaJSBundlerPlugin)
    .dependsOn(
      gcp.js,
      uri.js,
      viewerCommon.js,

      testing.js % "test->compile"
    )

lazy val viewerServer =
  project
    .settings(
      dep(
        http4s.`blaze-server`,
        http4s. circe,
        http4s. dsl,
        "ch.qos.logback" ^ "logback-classic" ^ "1.2.3"
      ),
      // Allows to read the JS generated by client
      resources in Compile ++= {
        (webpack in (viewerClient, Compile, fastOptJS)).value.map(_.data)
      },
      // rebuild JS on reStart
      reStart := (reStart dependsOn (webpack in (viewerClient, Compile, fastOptJS))).evaluated,
      // reStart if a client scala.js file changes
      watchSources ++= (watchSources in viewerClient).value,
      fork := false
    )
    .dependsOn(
      viewerCommon.jvm
    )

lazy val  xscala    = cross.settings()
lazy val `xscala-x` = xscala.x

lazy val zarr =
  cross
    .settings(
      dep(
        circe,
        circe.generic,
        circe.parser,
        hammerlab.bytes,
        hammerlab.io,
        hammerlab.math.utils,
        hammerlab.shapeless_utils,
        hammerlab.types,

        sourcecode,

        kittens,
        magnolia
      ),
      kindProjector,
      partialUnification,
      excludeFilter in sbt.Test := NothingFilter,
      testFrameworks += new TestFramework("utest.runner.Framework"),
    )
    .jvmSettings(
      dep(
        "org.lasersonlab" ^ "jblosc" ^ "1.0.1"
      )
    )
    .jsSettings(
      jsDependencies += "org.webjars.npm" % "pako" % "1.0.7" / "pako.js",
      jsEnv := new NodeJSEnv(NodeJSEnv.Config().withArgs("--max-old-space-size=2048" :: Nil))  // TODO: factor out
    )
    .dependsOn(
      `circe-utils`,
       ndarray,
         slist,
       testing % "test->compile",
           uri,
        xscala
    )
lazy val `zarr-x` = zarr.x

lazy val all =
  root(
    `blosc-x`,
    `circe-utils-x`,
    `cloud-x`,
     convert,
    `ndarray-x`,
     netcdf,
     singlecell,
    `slist-x`,
    `testing-x`,
    `uri-x`,
     utils,
    `xscala-x`,
    `zarr-x`
  )
