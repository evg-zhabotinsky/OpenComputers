package li.cil.oc.client.renderer.tileentity

import com.google.common.cache.{RemovalNotification, RemovalListener, CacheBuilder}
import cpw.mods.fml.common.{TickType, ITickHandler}
import java.util
import java.util.concurrent.{Callable, TimeUnit}
import li.cil.oc.client.Textures
import li.cil.oc.common.tileentity.Hologram
import li.cil.oc.util.RenderState
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer
import net.minecraft.tileentity.TileEntity
import org.lwjgl.opengl._
import scala.util.Random
import org.lwjgl.BufferUtils
import li.cil.oc.{OpenComputers, Settings}
import java.nio.ByteBuffer

object HologramRenderer extends TileEntitySpecialRenderer with Callable[Int] with RemovalListener[TileEntity, Int] with ITickHandler {
  private val random = new Random()

  /** We cache the VBOs for the projectors we render for performance. */
  private val cache = com.google.common.cache.CacheBuilder.newBuilder().
    expireAfterAccess(5, TimeUnit.SECONDS).
    removalListener(this).
    asInstanceOf[CacheBuilder[Hologram, Int]].
    build[Hologram, Int]()

  /**
   * Common for all holograms. Holds the vertex positions, texture
   * coordinates and normals information. Layout is: (u v) (s t p) (nx ny nz) (x y z)
   *
   * NOTE: this optimization only works if all the holograms have the
   * same dimensions (in voxels). If we ever need holograms of different
   * sizes we could probably just fake that by making the outer layers
   * immutable (i.e. always empty).
   */
  private var commonBuffer = 0

  /**
   * Also common for all holograms. Temporary buffers used to upload
   * hologram data to GPU. Store voxel colors (RGBA 4-byte format)
   */
  private var dataBuffer: ByteBuffer = null

  /** Used to pass the current screen along to call(). */
  private var hologram: Hologram = null

  override def renderTileEntityAt(te: TileEntity, x: Double, y: Double, z: Double, f: Float) {
    RenderState.checkError(getClass.getName + ".renderTileEntityAt: entering (aka: wasntme)")

    hologram = te.asInstanceOf[Hologram]
    if (!hologram.hasPower) return

    GL11.glPushClientAttrib(GL11.GL_ALL_CLIENT_ATTRIB_BITS)
    GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
    RenderState.makeItBlend()
    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)

    val playerDistSq = x * x + y * y + z * z
    val maxDistSq = hologram.getMaxRenderDistanceSquared
    val fadeDistSq = hologram.getFadeStartDistanceSquared
    RenderState.setBlendAlpha(0.75f * (if (playerDistSq > fadeDistSq) math.max(0, 1 - ((playerDistSq - fadeDistSq) / (maxDistSq - fadeDistSq)).toFloat) else 1))

    GL11.glPushMatrix()
    GL11.glTranslated(x + 0.5, y + 0.5, z + 0.5)
    GL11.glScaled(1.001, 1.001, 1.001) // Avoid z-fighting with other blocks.
    GL11.glTranslated(-1.5 * hologram.scale, 0, -1.5 * hologram.scale)

    // Do a bit of flickering, because that's what holograms do!
    if (Settings.get.hologramFlickerFrequency > 0 && random.nextDouble() < Settings.get.hologramFlickerFrequency) {
      GL11.glScaled(1 + random.nextGaussian() * 0.01, 1 + random.nextGaussian() * 0.001, 1 + random.nextGaussian() * 0.01)
      GL11.glTranslated(random.nextGaussian() * 0.01, random.nextGaussian() * 0.01, random.nextGaussian() * 0.01)
    }

    // After the below scaling, hologram is drawn inside a [0..48]x[0..32]x[0..48] box
    GL11.glScaled(hologram.scale / 16f, hologram.scale / 16f, hologram.scale / 16f)

    // Normalize normals (yes, glScale scales them too).
    GL11.glEnable(GL11.GL_NORMALIZE)

    GL11.glEnable(GL11.GL_CULL_FACE)
    GL11.glCullFace(GL11.GL_BACK)

    val holoTexture3D = cache.get(hologram, this)
    draw(holoTexture3D)

    GL11.glPopMatrix()
    GL11.glPopAttrib()
    GL11.glPopClientAttrib()

    RenderState.checkError(getClass.getName + ".renderTileEntityAt: leaving")
  }

  def draw(holoTexture3D: Int) {
    RenderState.checkError(getClass.getName + ".draw: entering (aka: wasntme)")
    initialize()
    RenderState.checkError(getClass.getName + ".draw: initialize")
    validate(holoTexture3D)
    RenderState.checkError(getClass.getName + ".draw: validate")
    publish(holoTexture3D)
    RenderState.checkError(getClass.getName + ".draw: publish")
  }

  private def initialize() {
    // First run only, create structure information.
    if (commonBuffer == 0) {
      // Using max() for clarity. First argument is definitely larger. (1 + 3) is (no cube) + 3 palette colors.
      dataBuffer = BufferUtils.createByteBuffer(math.max(hologram.width * hologram.width * hologram.height * 4, (1 + 3) * 4))

      commonBuffer = GL15.glGenBuffers()

      // Layout is: (u v) (s t p) (nx ny nz) (x y z)
      val data = BufferUtils.createFloatBuffer((hologram.width + hologram.width + hologram.height) * 2 * 4 * (2 + 3 + 3 + 3))

      // Stuff to add vertices "conveniently". Might be done better but I don't know Scala well enough.
      var nx: Float = 0
      var ny: Float = 0
      var nz: Float = 0
      def setNormal(x: Float, y: Float, z: Float) {
        nx = x
        ny = y
        nz = z
      }
      def addVertex(x: Float, y: Float, z: Float, u: Float, v: Float, s: Float, t: Float, p: Float) {
        data.put(u)
        data.put(v)
        data.put(s)
        data.put(t)
        data.put(p)
        data.put(nx)
        data.put(ny)
        data.put(nz)
        data.put(x)
        data.put(y)
        data.put(z)
        OpenComputers.log.info("# "+u+" "+v+" "+s+" "+t+" "+p+" "+nx+" "+ny+" "+nz+" "+x+" "+y+" "+z)
      }

      /**
       * Render hologram as 6 plane piles, each of which
       * covers one side of all cubes. Cube sides are as follows:
       *     0---1
       *     | N |
       * 0---3---2---1---0
       * | W | U | E | D |
       * 5---6---7---4---5
       *     | S |
       *     5---4
       */

      val dw = 1f / hologram.texWidth
      val dh = 1f / hologram.texHeight
      val hw = hologram.width
      val hh = hologram.height
      val tw = dw * hw
      val th = dh * hh

      // South
      setNormal(0, 0, 1)
      for (z <- 1 to hologram.width) {
        val tz = (z - .5f) * dw
        addVertex(hw, hh, z , hw, hh, tw, th, tz) // 5
        addVertex(0 , hh, z , 0 , hh, 0 , th, tz) // 4
        addVertex(0 , 0 , z , 0 , 0 , 0 , 0 , tz) // 7
        addVertex(hw, 0 , z , hw, 0 , tw, 0 , tz) // 6
      }
      // North
      setNormal(0, 0, -1)
      for (z <- hologram.width-1 to 0 by -1) {
        val tz = (z + .5f) * dw
        addVertex(hw, 0 , z , 0 , 0 , tw, 0 , tz) // 3
        addVertex(0 , 0 , z , hw, 0 , 0 , 0 , tz) // 2
        addVertex(0 , hh, z , hw, hh, 0 , th, tz) // 1
        addVertex(hw, hh, z , 0 , hh, tw, th, tz) // 0
      }

      // East
      setNormal(1, 0, 0)
      for (x <- 1 to hologram.width) {
        val tx = (x - .5f) * dw
        addVertex(x , hh, hw, 0 , hh, tx, th, tw) // 5
        addVertex(x , 0 , hw, 0 , 0 , tx, 0 , tw) // 6
        addVertex(x , 0 , 0 , hw, 0 , tx, 0 , 0 ) // 3
        addVertex(x , hh, 0 , hw, hh, tx, th, 0 ) // 0
      }
      // West
      setNormal(-1, 0, 0)
      for (x <- hologram.width-1 to 0 by -1) {
        val tx = (x + .5f) * dw
        addVertex(x , 0 , hw, hw, 0 , tx, 0 , tw) // 7
        addVertex(x , hh, hw, hw, hh, tx, th, tw) // 4
        addVertex(x , hh, 0 , 0 , hh, tx, th, 0 ) // 1
        addVertex(x , 0 , 0 , 0 , 0 , tx, 0 , 0 ) // 2
      }

      // Up
      setNormal(0, 1, 0)
      for (y <- 1 to hologram.height) {
        val ty = (y - .5f) * dh
        addVertex(hw, y , 0 , 0 , hw, tw, ty, 0 ) // 0
        addVertex(0 , y , 0 , hw, hw, 0 , ty, 0 ) // 1
        addVertex(0 , y , hw, hw, 0 , 0 , ty, tw) // 4
        addVertex(hw, y , hw, 0 , 0 , tw, ty, tw) // 5
      }
      // Down
      setNormal(0, -1, 0)
      for (y <- hologram.height-1 to 0 by -1) {
        val ty = (y + .5f) * dh
        addVertex(hw, y , hw, 0 , 0 , tw, ty, tw) // 6
        addVertex(0 , y , hw, hw, 0 , 0 , ty, tw) // 7
        addVertex(0 , y , 0 , hw, hw, 0 , ty, 0 ) // 2
        addVertex(hw, y , 0 , 0 , hw, tw, ty, 0 ) // 3
      }

      // Important! OpenGL will start reading from the current buffer position.
      data.rewind()

      // This buffer never ever changes, so static is the way to go.
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, commonBuffer)
      GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW)
    }
  }

  private def validate(holoTexture3D: Int) {
    // Refresh 3D texture when the hologram's data changed.
    if (hologram.dirty) {
      def value(hx: Int, hy: Int, hz: Int) = if (hx >= 0 && hy >= 0 && hz >= 0 && hx < hologram.width && hy < hologram.height && hz < hologram.width) hologram.getColor(hx, hy, hz) else 0

      // Copy color information. Use ByteBuffer to avoid thinking about endianness.
      // Only need to update dirty interval.
      for (hx <- 0 until hologram.width) {
        for (hy <- 0 until hologram.height) {
          for (hz <- 0 until hologram.width) {
            val v = value(hx, hy, hz)
            if (v == 0) { // No voxel here. (i.e. absolutely transparent voxel)
              dataBuffer.put(0.toByte) // R
              dataBuffer.put(0.toByte) // G
              dataBuffer.put(0.toByte) // B
              dataBuffer.put(0.toByte) // A
            } else { // Voxel present. (Treat is there as absolutely opaque)
              val color = hologram.colors(v - 1)
              dataBuffer.put(((color >>> 16) & 0xFF).toByte) // R
              dataBuffer.put(((color >>> 8) & 0xFF).toByte) // G
              dataBuffer.put((color & 0xFF).toByte) // B
              dataBuffer.put(0xFF.toByte) // A
            }
          }
        }
      }
      dataBuffer.flip()

      GL11.glBindTexture(GL12.GL_TEXTURE_3D, holoTexture3D)
      GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1)
      GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, hologram.width, hologram.height, hologram.width, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, dataBuffer)

      // Reset for the next operation.
      dataBuffer.clear()

      hologram.dirty = false
    }
  }

  private def publish(holoTexture3D: Int) {
    // Prepare geometry arrays and textures

    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, commonBuffer) // Layout: (u v) (s t p) (nx ny nz) (x y z)

    //GL11.glTexCoordPointer(2, GL11.GL_FLOAT, (2+3+3+3)*4, 0) // (u v) coordinates for striped overlay
    //GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY)

    //GL13.glClientActiveTexture(GL13.GL_TEXTURE1) // Switch to second texture (for array manipulation)
    GL11.glTexCoordPointer(3, GL11.GL_FLOAT, (2+3+3+3)*4, 2*4) // (s t p) coordinates for hologram contents
    GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY)

    GL11.glNormalPointer(GL11.GL_FLOAT, (2+3+3+3)*4, (2+3)*4) // Normals for lighting
    GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY)

    GL11.glVertexPointer(3, GL11.GL_FLOAT, (2+3+3+3)*4, (2+3+3)*4) // Vertex coordinates
    GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY)

    //bindTexture(Textures.blockHologram)

    //GL13.glActiveTexture(GL13.GL_TEXTURE1) // Switch to second texture (for most configuration)
    GL11.glEnable(GL12.GL_TEXTURE_3D)
    GL11.glBindTexture(GL12.GL_TEXTURE_3D, holoTexture3D)
    GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE)

    GL11.glColor4f(1,1,1,1) // Works like color filter - don't know how to disable so make it do nothing

    // We do two passes here to avoid weird transparency effects: in the first
    // pass we find the front-most fragment, in the second we actually draw it.
    // When we don't do this the hologram will look different from different
    // angles (because some faces will shine through sometimes and sometimes
    // they won't), so a more... consistent look is desirable.

    // First render pass: depth buffer produced
    GL11.glColorMask(false, false, false, false)
    GL11.glDepthMask(true)
    GL11.glDrawArrays(GL11.GL_QUADS, 0, (hologram.width + hologram.width + hologram.height) * 2 * 4)

    // Second render pass: image produced
    GL11.glColorMask(true, true, true, true)
    GL11.glDepthFunc(GL11.GL_EQUAL)
    GL11.glDrawArrays(GL11.GL_QUADS, 0, (hologram.width + hologram.width + hologram.height) * 2 * 4)
  }

  // ----------------------------------------------------------------------- //
  // Cache
  // ----------------------------------------------------------------------- //

  def call = {
    RenderState.checkError(getClass.getName + ".call: entering (aka: wasntme)")
    val holoTexture3D = GL11.glGenTextures()

    GL11.glBindTexture(GL12.GL_TEXTURE_3D, holoTexture3D)
    GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, 4, hologram.texWidth, hologram.texHeight, hologram.texWidth, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null.asInstanceOf[ByteBuffer])
    GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
    GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
    RenderState.checkError(getClass.getName + ".call: leaving")

    // Force re-indexing.
    hologram.dirty = true

    holoTexture3D
  }

  def onRemoval(e: RemovalNotification[TileEntity, Int]) {
    val glBuffer = e.getValue
    GL15.glDeleteBuffers(glBuffer)
    dataBuffer.clear()
  }

  // ----------------------------------------------------------------------- //
  // ITickHandler
  // ----------------------------------------------------------------------- //

  def getLabel = "OpenComputers.Hologram"

  def ticks() = util.EnumSet.of(TickType.CLIENT)

  def tickStart(tickType: util.EnumSet[TickType], tickData: AnyRef*) = cache.cleanUp()

  def tickEnd(tickType: util.EnumSet[TickType], tickData: AnyRef*) {}
}
