import java.io.{PrintWriter, File}
import java.nio.file.{Paths, Files}

import org.jsoup.Jsoup
import org.jsoup.nodes

import scala.collection.JavaConverters._

import scalaj.http._

object MDNParser {
  case class Element(tag: String, description: String, attributes: Seq[Attribute])
  case class Attribute(name: String, deprecated: Boolean, description: String, tpe: Option[String] = None)
  case class AttributeType(name: String, tpe: String)

  val cache = new File("cache/")
  cache.mkdir()

  val ElementsUrl = "https://developer.mozilla.org/en-US/docs/Web/HTML/Element"
  val GlobalAttributesUrl = "https://developer.mozilla.org/en-US/docs/Web/HTML/Global_attributes"
  val AdditionalTagUrls = Set("a", "b", "br", "span", "em", "strong", "code")
    .map(ElementsUrl + "/" + _)

  def encodeFileName(url: String) =
    url.flatMap {
      case ':' | '/' => Seq('_')
      case c => Seq(c)
    } + ".html"

  def printToFile(f: java.io.File)(op: java.io.PrintWriter => Unit) {
    val p = new java.io.PrintWriter(f)
    try op(p) finally p.close()
  }

  def request(url: String): String = {
    val fileName = encodeFileName(url)
    val file = new File(cache, fileName)

    if (file.exists()) io.Source.fromFile(file).mkString
    else {
      val response = Http(url).asString

      response.location match {
        case Some(location) =>
          val link = Paths.get(file.getPath)
          val target = Paths.get(encodeFileName(location))
          Files.createSymbolicLink(link, target)

          request(location)

        case _ =>
          val result = response.body
          printToFile(file)(_.write(result))
          result
      }
    }
  }

  def writeGlobalAttrs(path: File,
                       elements: Set[Element],
                       attributes: Seq[Attribute]): File = {
    val file = new File(path, "HTMLTag.scala")
    printToFile(file) { p =>
      writeHeader(p)
      p.println(s"""trait HTMLTag { self: tree.Tag =>""")
      writeAttributes(p, attributes)
      p.println("}")
      p.println()
      p.println("""object HTMLTag {""")
      p.println("""  def fromTag(tag: String): tree.Tag =""")
      p.println("""    tag match {""")
      elements.toList.sortBy(_.tag).foreach { element =>
        val className = escapeScalaName(element.tag)
        p.println(s"""      case "${element.tag}" => new $className""")
      }
      p.println("""    }""")
      p.println("""}""")
    }
    file
  }

  def writeHeader(p: PrintWriter) {
    p.println("package pl.metastack.metaweb.tag")
    p.println()
    p.println("import pl.metastack.metaweb.tree")
    p.println()
  }

  def writeElement(path: File,
                   globalAttributes: Seq[Attribute],
                   element: Element): File = {
    val file = new File(path, element.tag + ".scala")
    printToFile(file) { p =>
      writeHeader(p)
      val scalaTagName = escapeScalaName(element.tag)

      // TODO Use absolute URLs
      val description = escapeScalaComment(element.description)

      p.println( s"""/**""")
      p.println( s""" * $description""")
      p.println( s""" */""")
      p.println(s"""class $scalaTagName extends tree.Tag("${element.tag}") with HTMLTag {""")

      val uniqueAttrs = element.attributes.filter { attr =>
        val exists = globalAttributes.exists(_.name == attr.name)
        if (exists) println(s"`${element.tag}` redefines global attribute `${attr.name}`")
        !exists
      }

      writeAttributes(p, uniqueAttrs)
      p.println("}")
      p.println()
      p.println(s"""object $scalaTagName { def apply() = new $scalaTagName }""")
    }
    file
  }

  def escapeScalaName(name: String): String =
    name match {
      case "type" | "class" | "object" | "for" => "`" + name + "`"
      case _ if name.contains("-") => "`" + name + "`"
      case _ => name
    }

  def escapeScalaComment(comment: String): String =
    comment.replaceAll("""\/\*""", "/")

  def mapDomType(tpe: String): String =
    tpe match {
      case "DOMString" => "String"
      case "unsigned long" => "Long"
      case "long" => "Long"
      case "double" => "Double"
      case "boolean" => "Boolean"
      case _ if tpe.startsWith("HTML") => "tree.Node"  // TODO Generate interfaces
      case _ => tpe
    }

  def writeAttributes(p: PrintWriter, attributes: Seq[Attribute]) {
    attributes.foreach { attribute =>
      if (attribute.name != "data-*") {
        val attrName = escapeScalaName(attribute.name)
        val attrType = attribute.tpe.map(mapDomType).getOrElse("String")
        val description = escapeScalaComment(attribute.description)

        p.println( s"""  /**""")
        p.println( s"""   * $description""")
        p.println( s"""   */""")
        p.println( s"""  def $attrName: Option[$attrType] = attributes.get("${attribute.name}").asInstanceOf[Option[$attrType]]""")
        p.println( s"""  def $attrName(value: $attrType) = attributes.insertOrUpdate("${attribute.name}", value)""")
      }
    }
  }

  def globalAttributes(): Seq[Attribute] = {
    val document = Jsoup.parse(request(GlobalAttributesUrl))
    document.setBaseUri(GlobalAttributesUrl)

    parseAttributes(document)
  }

  def parseAttributes(document: nodes.Document): Seq[Attribute] = {
    val attributes = document.select("article > dl > dt").asScala

    attributes.flatMap { attr =>
      val docs = attr.nextElementSibling() match {
        case next if next != null && next.nodeName() == "dd" => next.html()
        case _ => ""
      }

      val name = attr.select("code").text()

      // See https://developer.mozilla.org/en-US/docs/Web/HTML/Element/li
      val deprecated = attr.select("i.icon-thumbs-down-alt").size() != 0

      // See https://developer.mozilla.org/en-US/docs/Web/HTML/Element/link
      val deleted = attr.select("i.icon-trash").size() != 0

      // See https://developer.mozilla.org/en-US/docs/Web/HTML/Element/a
      val obsolete = attr.select("span.obsolete").size() != 0

      if (name.isEmpty) None
      else Some(Attribute(name, deprecated || deleted || obsolete, docs))
    }
  }

  def processInterface(url: String): Option[Seq[AttributeType]] = {
    val document = Jsoup.parse(request(url))

    // For https://developer.mozilla.org/en-US/docs/Web/API/HTMLTemplateElement
    if (document.select("h1").text() == "Not Found") None
    else {
      val table = document.select("table.standard-table").first
      val properties = table.select("tr").asScala

      // For https://developer.mozilla.org/en-US/docs/Web/API/HTMLElement
      if (properties.headOption.exists(x =>
        Option(x.select("th").first())
          .exists(_.text() == "Specification"))) Some(Seq.empty)
      else
        Some(properties.tail.flatMap { property =>
          val td = property.select("td")
          val name = td.get(0).select("code").text()
          val tpe = td.get(1).select("code").text()

          assert(name.nonEmpty)

          if (tpe.nonEmpty) Some(AttributeType(name, tpe))
          else {
            println(s"$url: Attribute `$name` does not have a type")
            None
          }
        })
    }
  }

  def processMetaBox(parentUrl: String, element: nodes.Element): Seq[AttributeType] = {
    val domInterface = element
      .select("li")
      .asScala
      .find(_.select("dfn").text() == "DOM interface")

    domInterface.flatMap { itf =>
      val url = itf.select("a").first().absUrl("href")
      val attributes = processInterface(url)
      if (attributes.isEmpty) println(s"Interface $url (linked to from $parentUrl) does not exist")
      attributes
    }.getOrElse(Seq.empty)
  }

  def mergeAttributes(parentUrl: String,
                      attributes: Seq[Attribute],
                      types: Seq[AttributeType]): Seq[Attribute] = {
    types.foreach { case AttributeType(name, tpe) =>
      if (!attributes.exists(_.name == name)) {
        println(s"$parentUrl: Attribute `$name` missing")
      }
    }

    attributes.map { case attribute @ Attribute(name, _, _, _) =>
      types.find(_.name == name) match {
        case Some(AttributeType(_, tpe)) => attribute.copy(tpe = Some(tpe))
        case None =>
          println(s"$parentUrl: Could not find type for `$name` in linked interface")
          attribute
      }
    }
  }

  def processElement(url: String): Option[Element] = {
    val document = Jsoup.parse(request(url))
    document.setBaseUri(url)

    val article = document.select("article")

    // https://developer.mozilla.org/en-US/docs/Web/HTML/Element/decorator
    if (article.select("h1").text() == "Document Deleted") None
    // https://developer.mozilla.org/en-US/docs/Web/HTML/Element/noembed
    else if (article.select("div.nonStandard").size() != 0) None
    else {
      val tag = url.split('/').last

      // Read contents between first headline ("Summary") and second ("Attributes")
      val headlines = article.select("h2")
      val (fst, snd) = (headlines.first(), headlines.get(1))
      val articleChildren = article.first().children()
      val descriptionElements = articleChildren.subList(
        fst.elementSiblingIndex() + 1,
        snd.elementSiblingIndex())

      // Find box with meta information
      val attributes = descriptionElements.asScala.find(_.hasClass("htmlelt")) match {
        case Some(meta) =>
          mergeAttributes(url,
            parseAttributes(document),
            processMetaBox(url, meta))
        case None => parseAttributes(document)
      }

      val description = descriptionElements.asScala
        .filterNot(_.hasClass("htmlelt"))  // Filter box with meta information
        .map(_.html())
        .mkString("\n")

      Some(Element(tag, description, attributes))
    }
  }

  def createFiles(managedPath: File) = {
    val tagsPath = new File(managedPath, "pl/metastack/metaweb/tag")
    tagsPath.mkdirs()

    val document = Jsoup.parse(request(ElementsUrl))
    document.setBaseUri(ElementsUrl)

    val elements = document.select("""[style="vertical-align: top;"]""")
      .select("a")
      .asScala
      .map(_.absUrl("href"))
      .toSet

    AdditionalTagUrls.foreach { additionalTag =>
      if (!elements.contains(additionalTag))
        println(s"Link $additionalTag missing in index")
    }

    val globalAttrs = globalAttributes()

    val parsedElements = (elements ++ AdditionalTagUrls).flatMap(processElement)
    val elemsFiles = parsedElements.map(writeElement(tagsPath, globalAttrs, _))
    val attrsFile = writeGlobalAttrs(tagsPath, parsedElements, globalAttrs)

    elemsFiles.toSeq :+ attrsFile
  }
}