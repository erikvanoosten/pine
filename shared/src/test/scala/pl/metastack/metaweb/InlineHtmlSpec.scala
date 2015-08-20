package pl.metastack.metaweb

import scala.collection.mutable.ArrayBuffer

import pl.metastack.metarx.{Buffer, Var}

import minitest._

object InlineHtmlSpec extends SimpleTestSuite {
  test("toHtml") {
    val url = Var("http://github.com/")
    val title = Var("GitHub")
    val parsedHtml = html"""<a href=$url>$title</a>"""

    // translates into:
    // val root = tag.a()
    // root.bind("href", url)
    // root += Text(title)

    parsedHtml match {
      case root: tag.a =>
        assertEquals(root.toHtml, """<a href="http://github.com/">GitHub</a>""")

        url := "http://google.com/"
        assertEquals(root.href.get, "http://google.com/")
        assertEquals(root.toHtml, """<a href="http://google.com/">GitHub</a>""")

        root.clearChildren()
        assertEquals(root.toHtml, """<a href="http://google.com/"></a>""")
    }
  }

  test("toHtmlLive") {
    val url = Var("http://github.com/")
    val title = Var("GitHub")
    val parsedHtml = html"""<a href=$url>$title</a>"""

    parsedHtml match {
      case root: tag.a =>
        val changes = ArrayBuffer.empty[String]

        root.toHtmlLive.attach(changes += _)

        title := "Google"
        url := "http://google.com/"

        assertEquals(changes, Seq(
          """<a href="http://github.com/">GitHub</a>""",
          """<a href="http://github.com/">Google</a>""",
          """<a href="http://google.com/">Google</a>"""
        ))
    }
  }

  test("Bind list") {
    val tpl = html"""<div id="list"></div>"""

    tpl match {
      case list: tag.div =>
        list.bindChildren(Buffer("a", "b", "c").map { i =>
          val title = Var(s"Title $i")
          val subtitle = Var(s"Subtitle $i")
          html"""<div><div>$title</div><div>$subtitle</div></div>"""
        })

        assertEquals(list.contents.get.size, 3)
        assertEquals(list.contents.get.last.toHtml,
          """<div><div>Title c</div><div>Subtitle c</div></div>""")
    }
  }

  // TODO Generate in MDNParser for all events and tags
  implicit class ButtonWithClick(button: tag.button) {
    def click() {
      button.events.get("click").foreach(_(()))
    }
  }

  test("Inline event handler") {
    var clicked = 0
    val tpl = html"""<button onclick="${(_: Any) => clicked += 1}">Test</button>"""

    tpl match {
      case btn: tag.button =>
        btn.click()
        btn.disabled(true)
        assertEquals(clicked, 1)
    }
  }

  test("Function as event handler") {
    var clicked = 0
    def click(event: Any) { clicked += 1 }
    val tpl = html"""<button onclick="${click(_: Any)}">Test</button>"""

    tpl match {
      case btn: tag.button =>
        btn.click()
        assertEquals(clicked, 1)
    }
  }

  test("String placeholder") {
    val text = "Hello world"
    val div = html"<div>$text</div>"
    assertEquals(div.toHtml, "<div>Hello world</div>")
  }

  test("Node placeholder") {
    val span = html"<span>test</span>"
    val div = html"<div>$span</div>"
    assertEquals(div.toHtml, "<div><span>test</span></div>")
  }

  test("Seq[Node] placeholders") {
    val spans = Seq(
      html"<span>test</span>",
      html"<span>test2</span>"
    )

    val div = html"<div>$spans</div>"
    assertEquals(div.toHtml, "<div><span>test</span><span>test2</span></div>")
  }
}
