package org.broadinstitute.hail.annotations

case class Annotations[T](maps: Map[String, Map[String, T]], vals: Map[String, T]) extends Serializable {

  def nAttrs: Int = maps.map(_._2.size).sum + vals.size

  def hasMap(str: String): Boolean = maps.contains(str)

  def containsVal(str: String): Boolean = vals.contains(str)

  def containsInMap(parent: String, str: String): Boolean = hasMap(parent) && maps(parent).contains(str)

  def getVal(str: String): Option[T] = vals.get(str)

  def getInMap(parent: String, str: String): Option[T] =
    maps.get(parent).flatMap(_.get(str))

  def getMap(parent: String): Option[Map[String, T]] = maps.get(parent)

  def addMap(name: String, m: Map[String, T]): Annotations[T] =
    Annotations(maps + ((name, m)), vals)

  def addMaps(newMaps: Map[String, Map[String, T]]): Annotations[T] =
    Annotations(maps ++ newMaps, vals)

  def addVal(name: String, mapping: T): Annotations[T] = Annotations(maps, vals + ((name, mapping)))

  def addVals(newVals: Map[String, T]): Annotations[T] = Annotations(maps, vals ++ newVals)

  def ++(other: Annotations[T]): Annotations[T] = {
    new Annotations(maps ++ other.maps, vals ++ other.vals)
  }
}

object Annotations {

  def empty[T](): Annotations[T] =
    Annotations(Map.empty[String, Map[String, T]], Map.empty[String, T])

  def emptyOfSignature(): AnnotationSignatures = empty[AnnotationSignature]()

  def emptyOfString(): AnnotationData = empty[String]()

  def emptyOfArrayString(nSamples: Int): IndexedSeq[AnnotationData] =
    (0 until nSamples)
      .map(i => empty[String]())
}

object AnnotationUtils {

  def annotationToString(ar: AnyRef): String = {
    ar match {
      case iter: Iterable[_] => if (iter.isEmpty) "" else iter.map(_.toString).mkString(", ")
      case _ => ar.toString
    }
  }
}

object AnnotationClassBuilder {

  def signatures(sigs: AnnotationSignatures, hiddenClassName: String,
    makeToString: Boolean = false): String = {
    val internalClasses = sigs.maps.map {
      case (subclass, subMap) =>
        val attrs = subMap
          .map { case (k, sig) =>
            s"""  val $k: Option[${sig.emitType}] = subMap.get("$k").map(_.${sig.emitConversionIdentifier})"""
          }
          .mkString("\n")
        val methods: String = {
          if (makeToString) {
            s"""  def __fields: Array[String] = Array(
                |    ${subMap.keys.toArray.sorted.map(s => s"""toTSVString($s)""").mkString(",")}
                |  )
                |  override def toString: String = __fields.mkString(";")
                |  def all: String = __fields.mkString("\t")""".stripMargin
          } else ""
        }
        s"""class __${subclass}Annotations(subMap: Map[String, String]) extends Serializable {
            |$attrs
            |$methods
            |}""".stripMargin
    }
      .mkString("\n")

    val hiddenClass = {
      val classes =
        sigs.maps.map { case (subclass, subMap) =>
          s"""  val $subclass = new __${subclass}Annotations(annot.maps("$subclass"))"""
        }
          .mkString("\n")
      val vals = sigs.vals.map { case (k, sig) =>
        s"""  val $k: Option[${sig.emitType}] = annot.getVal("$k").map(_.${sig.emitConversionIdentifier})"""
      }
        .mkString("\n")
      s"""class ${hiddenClassName}Annotations(annot: org.broadinstitute.hail.annotations.AnnotationData)
          |  extends Serializable {
          |  ${if (classes.nonEmpty) classes else "// no classes"}
          |  ${if (vals.nonEmpty) vals else "// no vals"}
          |}
          |""".stripMargin
    }

    s"""
       |$internalClasses
       |$hiddenClass
    """.stripMargin
  }

  def instantiate(exposedName: String, hiddenClassName: String): String = {
    s"val $exposedName = new ${hiddenClassName}Annotations($hiddenClassName)\n"
  }

  def makeIndexedSeq(hiddenOutputName: String, hiddenClassName: String, hiddenAnnotationArrayName: String): String =
    s"""val $hiddenOutputName: IndexedSeq[${hiddenClassName}Annotations] =
        |$hiddenAnnotationArrayName.map(new ${hiddenClassName}Annotations(_))
        |
     """.stripMargin

  val arrayRegex = """Array\[(\w+)\]""".r
  val optionRegex = """Option\[(\w+)\]""".r

  private def getDefault(typeStr: String): String = {
    if (typeStr == "Int" || typeStr == "Double")
      "0"
    else if (typeStr == "Boolean")
      "false"
    else
      typeStr match {
        case optionRegex(subType) => "None"
        case arrayRegex(subType) => getDefault(subType)
        case _ => ""
      }
  }
}
