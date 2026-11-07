package app.gyrolet.mpvrx.data.network.client

import java.io.IOException
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class DlnaXmlTest {
  private val base = "http://media.example:8200/root/description.xml".toHttpUrl()

  // Models the Android factory, which supports namespaces/validation but not Xerces flags.
  private class AndroidLikeFactory : DocumentBuilderFactory() {
    private val delegate = newInstance()
    override fun newDocumentBuilder(): DocumentBuilder {
      delegate.isNamespaceAware = isNamespaceAware
      delegate.isExpandEntityReferences = isExpandEntityReferences
      return delegate.newDocumentBuilder()
    }
    override fun setFeature(name: String, value: Boolean) { throw ParserConfigurationException(name) }
    override fun getFeature(name: String): Boolean = throw ParserConfigurationException(name)
    override fun setAttribute(name: String, value: Any) { delegate.setAttribute(name, value) }
    override fun getAttribute(name: String): Any = delegate.getAttribute(name)
  }

  @Test fun parsesWhenAndroidDoesNotSupportXercesSecurityFlags() {
    val root = DlnaXml.parseWithFactory("<root xmlns='urn:test'><name>Music &amp; Video</name></root>", AndroidLikeFactory())
    assertEquals("urn:test", root.namespaceURI)
    assertEquals("Music & Video", root.textContent)
  }

  @Test(expected = IOException::class)
  fun androidCompatibilityDoesNotPermitDoctypeOrExternalEntities() {
    DlnaXml.parseWithFactory("<!DOCTYPE root [<!ENTITY external SYSTEM 'file:///should-not-be-read'>]><root>&external;</root>", AndroidLikeFactory())
  }

  @Test fun resolvesRelativeControlUrlAndKeepsDisplayName() {
    val device = DlnaXml.description("""<root xmlns="urn:schemas-upnp-org:device-1-0"><device>
      <friendlyName>My NAS</friendlyName><serviceList><service>
      <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
      <controlURL>/ctl/ContentDirectory</controlURL></service></serviceList></device></root>""", base)
    assertEquals("My NAS", device.name)
    assertEquals("http://media.example:8200/ctl/ContentDirectory", device.controlUrl.toString())
  }

  @Test fun parsesOrderedAlbumsAndAudioWithoutFilenameExtensions() {
    val response = """<BrowseResponse><Result><DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
      xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
      <container id="album/42"><dc:title>Album</dc:title><upnp:class>object.container.album.musicAlbum</upnp:class></container>
      <item id="opaque:track"><dc:title>Track title</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class>
      <res protocolInfo="http-get:*:audio/flac:DLNA.ORG_CI=0" size="3000000000">/track/42</res></item>
      </DIDL-Lite></Result><NumberReturned>2</NumberReturned><TotalMatches>2</TotalMatches></BrowseResponse>"""
    val page = DlnaXml.browseResponse(response, base)
    assertEquals(listOf('o', 'a'), page.entries.map { it.kind })
    assertEquals("audio/flac", page.entries[1].mimeType)
    assertEquals(3000000000L, page.entries[1].size)
    assertEquals("http://media.example:8200/track/42", page.entries[1].resource.toString())
  }

  @Test fun soapEscapesOpaqueObjectIds() {
    val body = DlnaXml.browseRequest("urn:schemas-upnp-org:service:ContentDirectory:1", "A&B<42>", false, 128, 128)
    assertTrue(body.contains("<ObjectID>A&amp;B&lt;42&gt;</ObjectID>"))
    assertTrue(body.contains("<StartingIndex>128</StartingIndex>"))
    assertTrue(body.contains("<SortCriteria></SortCriteria>"))
  }

  @Test(expected = IOException::class)
  fun rejectsOversizedXmlResponses() {
    DlnaXml.readBounded(ByteArray(8 * 1024 * 1024 + 1).inputStream())
  }
}
