package de.tobiasroeser.mill.vcs.version

import mill.T
import mill.api.Logger
import mill.define.{Discover, ExternalModule, Input, Module}
import os.SubprocessException

import scala.util.control.NonFatal

trait VcsVersion extends Module {

  def vcsBasePath: os.Path = millSourcePath

  /**
   * Calc a publishable version based on git tags and dirty state.
   *
   * @return A tuple of (the latest tag, the calculated version string)
   */
  def vcsState: Input[VcsState] = T.input { calcVcsState(T.log) }

  def calcVcsState(logger: Logger): VcsState = {
    val curHeadRaw =
      try {
        Option(os.proc("git", "rev-parse", "HEAD").call(cwd = vcsBasePath, stderr = os.Pipe).out.trim())
      } catch {
        case e: SubprocessException =>
          logger.error(s"${vcsBasePath} is not a git repository.")
          None
      }

    curHeadRaw match {
      case None =>
        VcsState("no-vcs", None, 0, None, None)

      case curHead =>
        // we have a proper git repo

        val exactTag =
          try {
            curHead
              .map(curHead =>
                os.proc("git", "describe", "--exact-match", "--tags", "--always", curHead)
                  .call(cwd = vcsBasePath, stderr = os.Pipe)
                  .out
                  .text()
                  .trim
              )
              .filter(_.nonEmpty)
          } catch {
            case NonFatal(_) => None
          }

        val lastTag: Option[String] = exactTag.orElse {
          try {
            Option(
              os.proc("git", "describe", "--abbrev=0", "--tags")
                .call(cwd = vcsBasePath, stderr = os.Pipe)
                .out
                .text()
                .trim()
            )
              .filter(_.nonEmpty)
          } catch {
            case NonFatal(_) => None
          }
        }

        val commitsSinceLastTag =
          if (exactTag.isDefined) 0
          else {
            curHead
              .map { curHead =>
                os.proc(
                  "git",
                  "rev-list",
                  curHead,
                  lastTag match {
                    case Some(tag) => Seq("--not", tag)
                    case _         => Seq()
                  },
                  "--count"
                ).call(cwd = vcsBasePath, stderr = os.Pipe)
                  .out
                  .trim()
                  .toInt
              }
              .getOrElse(0)
          }

        val dirtyHashCode: Option[String] = Option(os.proc("git", "diff")
                                                     .call(cwd = vcsBasePath, stderr = os.Pipe)
                                                     .out.text().trim()).flatMap {
          case "" => None
          case s  => Some(Integer.toHexString(s.hashCode))
        }

        new VcsState(
          currentRevision = curHead.getOrElse(""),
          lastTag = lastTag,
          commitsSinceLastTag = commitsSinceLastTag,
          dirtyHash = dirtyHashCode,
          vcs = Option(Vcs.git)
        )
    }
  }

}

object VcsVersion extends ExternalModule with VcsVersion {
  lazy val millDiscover = Discover[this.type]
}
