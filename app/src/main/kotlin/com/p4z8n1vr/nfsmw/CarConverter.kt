package com.p4z8n1vr.nfsmw

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Arrays
import java.util.Collections
import java.util.Comparator
import java.util.Locale
import java.util.TreeMap
import java.util.function.Consumer
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.*

/** Converts PC add-on cars into Xbox-readable game resources. */
internal object CarConverter {
    const val TEMPLATE_PARTS = "BMWM3"
    const val STOCK_TYPES = 84
    const val FIRST_ADDON_TYPE = 85
    private const val MAKE_OFFSET = 0x40
    private val MAKES = arrayOf("", "BMW", "FORD", "SUBARU", "PORSCHE", "AUDI", "MAZDA", "GENERAL_MOTORS", "DODGE", "TOYOTA",
        "MITSUBISHI", "MCLAREN", "MERCEDES", "NISSAN", "LOTUS", "LAMBORGHINI", "RENAULT", "LEXUS", "PONTIAC", "CHEVROLET", "VAUXHALL",
        "ASTONMARTIN", "VOLKSWAGEN", "FIAT", "CADILLAC", "CORVETTE")
    private fun makeIndex(manufacturer: String): Int {
        val at = MAKES.indexOf(manufacturer)
        if (at >= 0) return at
        val spaced = MAKES.indexOf(manufacturer.replace(" ", ""))
        return if (spaced >= 0) spaced else MAKES.indexOf(manufacturer.replace(' ', '_'))
    }
    const val MAX_ADDONS = 150
    const val MAX_TEXTURES_KB = 1536

    class Failure(@JvmField val reason: Reason, detail: String) : Exception(detail) {
        enum class Reason { NO_CAR, NAME_TOO_LONG, UNSUPPORTED }
    }
    class Package {
        @JvmField var car = ""
        @JvmField var title = ""
        @JvmField var manufacturer = ""
        @JvmField var geometry: ByteArray = byteArrayOf()
        @JvmField var textures: ByteArray = byteArrayOf()
        @JvmField var script: ByteArray? = null
        @JvmField var logo: ByteArray? = null
        @JvmField var logoWidth = 0
        @JvmField var logoHeight = 0
        @JvmField var logoFormat = 0
    }
    class Kept {
        @JvmField var car = ""
        @JvmField var title = ""
        @JvmField var manufacturer = ""
        lateinit var geometry: File
        lateinit var textures: File
        @JvmField var script: ByteArray? = null
        @JvmField var logo: ByteArray? = null
        @JvmField var logoWidth = 0
        @JvmField var logoHeight = 0
        @JvmField var logoFormat = 0
    }
    class Built {
        @JvmField val files: MutableMap<String, ByteArray?> = LinkedHashMap()
        @JvmField val addons: MutableList<String> = ArrayList()
        @JvmField val replaced: MutableList<String> = ArrayList()
    }
    private val WANTED = Pattern.compile(
        "(.*/)?([^/]+/(geometry|textures)\\.bin|cartypeinfo[^/]*\\.bin|[^/]*\\.nfsms|[^/]*\\.end" +
            "|secondary_logo_[^/]*\\.dds)"
    )
    @JvmStatic
    fun wanted(path: String): Boolean {
        val p = path.replace('\\', '/').lowercase(Locale.ROOT)
        return WANTED.matcher(p).matches() && !p.substring(p.lastIndexOf('/') + 1).contains("uninstall")
    }

    private fun align(n: Int, a: Int): Int = (n + a - 1) and (a - 1).inv()
    private fun i32(b: ByteArray, o: Int): Int = (b[o].toInt() and 255) or
        ((b[o + 1].toInt() and 255) shl 8) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24)
    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    private fun f32(b: ByteArray, o: Int): Float = Float.fromBits(i32(b, o))
    private fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o+1] = (v shr 8).toByte(); b[o+2] = (v shr 16).toByte(); b[o+3] = (v shr 24).toByte()
    }
    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o+1] = (v shr 8).toByte() }
    private fun putF(b: ByteArray, o: Int, v: Float) = put32(b, o, v.toRawBits())
    private fun ints(vararg values: Int): ByteArray = ByteArray(values.size * 4).also { out ->
        for (i in values.indices) put32(out, i * 4, values[i])
    }
    private fun latin(s: String) = s.toByteArray(StandardCharsets.ISO_8859_1)
    private fun latin(b: ByteArray, off: Int, n: Int) = String(b, off, n, StandardCharsets.ISO_8859_1)
    private fun text(b: ByteArray, off: Int, end: Int): String {
        var e = off
        while (e < end && b[e] != 0.toByte()) e++
        return latin(b, off, e - off)
    }
    private fun fill(n: Int, v: Int): ByteArray = ByteArray(maxOf(0, n)).also { if (v != 0) Arrays.fill(it, v.toByte()) }
    private fun sub(b: ByteArray, off: Int, end: Int): ByteArray = Arrays.copyOfRange(b, off, minOf(end, b.size))
    private fun find(b: ByteArray, what: ByteArray, from: Int): Int {
        var i = from
        while (i + what.size <= b.size) {
            var match = true
            for (j in what.indices) if (b[i+j] != what[j]) { match = false; break }
            if (match) return i
            i++
        }
        return -1
    }
    private fun rfind(b: ByteArray, what: ByteArray): Int {
        for (i in b.size-what.size downTo 0) {
            var match = true
            for (j in what.indices) if (b[i+j] != what[j]) { match = false; break }
            if (match) return i
        }
        return -1
    }

    private class Buf(private var a0: ByteArray, var n: Int) {
        var a: ByteArray = a0
        constructor(): this(ByteArray(256), 0)
        constructor(start: ByteArray): this(start, start.size)
        private fun room(more: Int) { if (n + more > a.size) a = Arrays.copyOf(a, maxOf(a.size * 2, n+more)) }
        fun add(b: ByteArray): Buf = add(b, 0, b.size)
        fun add(b: ByteArray, off: Int, len: Int): Buf {
            room(len); System.arraycopy(b, off, a, n, len); n += len; return this
        }
        fun zeros(len: Int): Buf {
            if (len <= 0) return this
            room(len); Arrays.fill(a, n, n+len, 0.toByte()); n += len; return this
        }
        fun bytes(): ByteArray = Arrays.copyOf(a, n)
    }
    private fun cat(vararg parts: ByteArray): ByteArray = Buf().also { for (p in parts) it.add(p) }.bytes()

    @JvmStatic
    fun bhash(s: String): Int {
        var h = -1
        for (c in latin(s)) h = h * 33 + (c.toInt() and 255)
        return h
    }
    private fun mix(a0: Int, b0: Int, c0: Int): IntArray {
        var a = a0; var b = b0; var c = c0
        a = a-b-c; a = a xor (c ushr 13)
        b = b-c-a; b = b xor (a shl 8)
        c = c-a-b; c = c xor (b ushr 13)
        a = a-b-c; a = a xor (c ushr 12)
        b = b-c-a; b = b xor (a shl 16)
        c = c-a-b; c = c xor (b ushr 5)
        a = a-b-c; a = a xor (c ushr 3)
        b = b-c-a; b = b xor (a shl 10)
        c = c-a-b; c = c xor (b ushr 15)
        return intArrayOf(a,b,c)
    }
    @JvmStatic
    fun attribKey(s: String): Int {
        val k = latin(s)
        var a = 0x9E3779B9.toInt(); var b = a; var c = 0xABCDEF00.toInt()
        var i = 0
        while (k.size - i >= 12) {
            val m = mix(a+i32(k,i), b+i32(k,i+4), c+i32(k,i+8))
            a = m[0]; b = m[1]; c = m[2]; i += 12
        }
        c += k.size
        for (j in k.size-i-1 downTo 0) {
            val v = k[i+j].toInt() and 255
            when {
                j >= 8 -> c += v shl (8 * (j-7))
                j >= 4 -> b += v shl (8 * (j-4))
                else -> a += v shl (8*j)
            }
        }
        return mix(a,b,c)[2]
    }
    @JvmStatic
    fun stringKey64(name: String): Long {
        val k = latin(name)
        var a = 0xABCDEF0011223344UL.toLong(); var b = a; var c = 0x9E3779B97F4A7C13UL.toLong()
        var i = 0
        while (k.size - i >= 24) {
            a += i64(k,i); b += i64(k,i+8); c += i64(k,i+16)
            val m = mix64(a,b,c)
            a = m[0]; b = m[1]; c = m[2]; i += 24
        }
        c += k.size.toLong()
        for (j in k.size-i-1 downTo 0) {
            val v = k[i+j].toLong() and 255L
            when {
                j >= 16 -> c += v shl (8*(j-15))
                j >= 8 -> b += v shl (8*(j-8))
                else -> a += v shl (8*j)
            }
        }
        return mix64(a,b,c)[2]
    }
    private fun i64(b: ByteArray, o: Int) = (i32(b,o).toLong() and 0xFFFFFFFFL) or (i32(b,o+4).toLong() shl 32)
    private fun mix64(a0: Long,b0: Long,c0: Long): LongArray {
        var a=a0; var b=b0; var c=c0
        a=a-b-c; a=a xor (c ushr 43)
        b=b-c-a; b=b xor (a shl 9)
        c=c-a-b; c=c xor (b ushr 8)
        a=a-b-c; a=a xor (c ushr 38)
        b=b-c-a; b=b xor (a shl 23)
        c=c-a-b; c=c xor (b ushr 5)
        a=a-b-c; a=a xor (c ushr 35)
        b=b-c-a; b=b xor (a shl 49)
        c=c-a-b; c=c xor (b ushr 11)
        a=a-b-c; a=a xor (c ushr 12)
        b=b-c-a; b=b xor (a shl 18)
        c=c-a-b; c=c xor (b ushr 22)
        return longArrayOf(a,b,c)
    }

    @JvmStatic
    fun jdlzDecode(b: ByteArray): ByteArray {
        require(isJdlz(b,0)) { "no JDLZ header" }
        val usize=i32(b,8); val csize=i32(b,12)
        val out=ByteArray(usize)
        var f1=(b[16].toInt() and 255) or 0x100
        var f2=(b[17].toInt() and 255) or 0x100
        var ip=18; var op=0; var left=csize-18
        while (left != 0) {
            require(left >= 0) { "JDLZ input does not end where its size says" }
            if ((f1 and 1) != 0) {
                val n: Int; val t: Int
                if ((f2 and 1) != 0) {
                    n=((b[ip+1].toInt() and 255) or ((b[ip].toInt() and 0xF0) shl 4)) + 3
                    t=(b[ip].toInt() and 0x0F) + 1
                } else {
                    t=((b[ip+1].toInt() and 255) or ((b[ip].toInt() and 0xE0) shl 3)) + 17
                    n=(b[ip].toInt() and 0x1F) + 3
                }
                require(op+n <= usize) { "JDLZ output past its size" }
                for (i in 0 until n) out[op+i] = out[op+i-t]
                op+=n; ip+=2; left-=2; f2=f2 shr 1
            } else {
                if (op < usize) out[op++] = b[ip++]
                left--
            }
            f1=f1 shr 1
            if (f1==1) { f1=(if(ip<b.size) b[ip].toInt() and 255 else 0) or 0x100; ip++; left-- }
            if (f2==1) { f2=(if(ip<b.size) b[ip].toInt() and 255 else 0) or 0x100; ip++; left-- }
        }
        require(op == usize) { "JDLZ output short of its size" }
        return out
    }
    private fun isJdlz(b:ByteArray,o:Int):Boolean = o+18<=b.size &&
        b[o]=='J'.code.toByte() && b[o+1]=='D'.code.toByte() && b[o+2]=='L'.code.toByte() && b[o+3]=='Z'.code.toByte()
    @JvmStatic
    fun jdlzEncode(d: ByteArray): ByteArray {
        val out=Buf(ByteArray(maxOf(256,d.size/2)),0)
        out.add(byteArrayOf('J'.code.toByte(),'D'.code.toByte(),'L'.code.toByte(),'Z'.code.toByte(),2,0x10,0,0))
        out.add(ints(d.size,0))
        val n=d.size; val head=IntArray(1 shl 18){-1}; val prev=IntArray(n)
        var f1At=-1; var f2At=-1; var f1N=8; var f2N=8; var f1=0; var f2=0; var i=0
        while (i<n) {
            if(f1N==8){if(f1At>=0)out.a[f1At]=f1.toByte();f1At=out.n;out.zeros(1);f1=0;f1N=0}
            if(f2N==8){if(f2At>=0)out.a[f2At]=f2.toByte();f2At=out.n;out.zeros(1);f2=0;f2N=0}
            var bestLen=0; var bestDist=0
            if(i+3<=n){
                var seen=0; var p=head[tri(d,i)]
                while(p>=0 && seen<16){
                    val dist=i-p
                    if(dist>2064)break
                    if(d[p]!=d[i] || d[p+1]!=d[i+1] || d[p+2]!=d[i+2]){p=prev[p];continue}
                    seen++
                    val limit=minOf(n-i,if(dist<=16)4098 else 34)
                    var m=3
                    while(m<limit && d[p+m]==d[i+m])m++
                    if(m>bestLen){bestLen=m;bestDist=dist;if(m==limit)break}
                    p=prev[p]
                }
                val h=tri(d,i);prev[i]=head[h];head[h]=i
            }
            if(bestLen>=3){
                f1=f1 or (1 shl f1N)
                if(bestDist<=16){
                    f2=f2 or (1 shl f2N)
                    out.add(byteArrayOf(((((bestLen-3) shr 8) shl 4) or (bestDist-1)).toByte(),(bestLen-3).toByte()))
                }else{
                    val t=bestDist-17
                    out.add(byteArrayOf(((((t shr 3) and 0xE0) or (bestLen-3))).toByte(),t.toByte()))
                }
                f2N++
                var j=i+1;val end=minOf(i+bestLen,n-2)
                while(j<end){val h=tri(d,j);prev[j]=head[h];head[h]=j;j++}
                i+=bestLen
            } else {out.add(d,i,1);i++}
            f1N++
        }
        if(f1At>=0)out.a[f1At]=f1.toByte()
        if(f2At>=0)out.a[f2At]=f2.toByte()
        if(f1N==8)out.zeros(1)
        if(f2N==8)out.zeros(1)
        put32(out.a,12,out.n)
        return out.bytes()
    }
    private fun tri(d:ByteArray,i:Int):Int{
        val v=(d[i].toInt() and 255) or ((d[i+1].toInt() and 255) shl 8) or ((d[i+2].toInt() and 255) shl 16)
        return (v*0x9E3779B1.toInt()) ushr 14
    }

    class Disc @Throws(IOException::class) constructor(path: File): AutoCloseable {
        private val PARTITIONS=longArrayOf(0L,0x18300000L,0xFD90000L,0x2080000L)
        @JvmField val recs: MutableMap<Int,IntArray> = TreeMap { a,b->Integer.compareUnsigned(a,b) }
        private val iso:RandomAccessFile?
        private val folder:File?
        private var base=0L
        private val nfs:MutableMap<String,LongArray> = HashMap()
        init {
            val zdir:ByteArray
            if(path.isDirectory){
                iso=null
                folder=child(path,"NFS")
                val files=folder?.listFiles() ?: throw IOException("no NFS folder in $path")
                for(f in files)nfs[f.name.uppercase(Locale.ROOT)]=longArrayOf(0L,f.length())
                zdir=readAll(child(folder,"ZDIR.BIN"))
            }else{
                folder=null
                iso=RandomAccessFile(path,"r")
                val magic=ByteArray(28)
                var found=false
                for(part in PARTITIONS){
                    if(part+32*2048+28>iso.length())continue
                    iso.seek(part+32*2048);iso.readFully(magic)
                    if(latin(magic,0,20)=="MICROSOFT*XBOX*MEDIA"){base=part;found=true;break}
                }
                if(!found)throw IOException("$path is not an Xbox disc image")
                val root=dir(i32(magic,20).toLong() and 0xFFFFFFFFL,i32(magic,24))
                val nf=root["NFS"] ?: throw IOException("no NFS folder in $path")
                nfs.putAll(dir(nf[0],nf[1].toInt()))
                val z=nfs["ZDIR.BIN"] ?: throw IOException("no ZDIR.BIN in $path")
                zdir=read(z[0],z[1].toInt())
            }
            var i=0
            while(i+24<=zdir.size){
                val r=IntArray(6){j->i32(zdir,i+j*4)}
                recs[r[0]]=r;i+=24
            }
        }
        private fun child(dir:File?,name:String):File?=dir?.listFiles()?.firstOrNull{it.name.equals(name,ignoreCase=true)}
        private fun readAll(f:File?):ByteArray{
            if(f==null)throw IOException("a game file is missing")
            return RandomAccessFile(f,"r").use{r->ByteArray(r.length().toInt()).also{r.readFully(it)}}
        }
        private fun read(sector:Long,size:Int):ByteArray{
            val b=ByteArray(size)
            iso!!.seek(base+sector*2048);iso.readFully(b)
            return b
        }
        private fun dir(sector:Long,size:Int):MutableMap<String,LongArray>{
            val out=HashMap<String,LongArray>()
            walk(read(sector,size),0,out,0)
            return out
        }
        private fun walk(t:ByteArray,off:Int,out:MutableMap<String,LongArray>,depth:Int){
            if(off*4+14>t.size || depth>64)return
            val left=u16(t,off*4);val right=u16(t,off*4+2);val nl=t[off*4+13].toInt() and 255
            if(left==0xFFFF)return
            out[latin(t,off*4+14,nl).uppercase(Locale.ROOT)]=longArrayOf(i32(t,off*4+4).toLong() and 0xFFFFFFFFL,i32(t,off*4+8).toLong() and 0xFFFFFFFFL)
            if(left!=0)walk(t,left,out,depth+1)
            if(right!=0)walk(t,right,out,depth+1)
        }
        fun archiveSize(i:Int):Long=nfs["ZZDATA$i.BIN"]?.get(1) ?: -1L
        @Throws(IOException::class)
        fun load(path:String):ByteArray{
            val r=recs[bhash(path)] ?: throw IOException("no $path in the game's files")
            val at=(r[2].toLong() and 0xFFFFFFFFL)*2048
            if(iso!=null){
                val arc=nfs["ZZDATA${r[1]}.BIN"] ?: throw IOException("no archive ${r[1]}")
                return read(arc[0]+at/2048,r[4])
            }
            val arc=child(folder,"ZZDATA${r[1]}.BIN") ?: throw IOException("no archive ${r[1]}")
            return RandomAccessFile(arc,"r").use{f->ByteArray(r[4]).also{f.seek(at);f.readFully(it)}}
        }
        @Throws(IOException::class)
        override fun close(){iso?.close()}
    }

    private fun chunks(b:ByteArray,off:Int,end:Int):MutableList<IntArray>{
        val out=ArrayList<IntArray>();var p=off
        while(p+8<=end){
            val c=i32(b,p);val s=i32(b,p+4)
            if(s<0 || p+8+s.toLong()>end)break
            out.add(intArrayOf(c,s,p+8));p+=8+s
        }
        return out
    }
    private fun walkAll(b:ByteArray,off:Int,end:Int):MutableList<IntArray> = ArrayList<IntArray>().also{walkInto(b,off,end,it)}
    private fun walkInto(b:ByteArray,off:Int,end:Int,out:MutableList<IntArray>){
        for(c in chunks(b,off,end)){out.add(c);if(c[0]<0)walkInto(b,c[2],c[2]+c[1],out)}
    }
    private fun first(b:ByteArray,id:Int):IntArray?=walkAll(b,0,b.size).firstOrNull{it[0]==id}
    private fun payload(b:ByteArray,o:Int,s:Int):Int{
        var p=o;while(p+4<=o+s && b[p]==0x11.toByte() && b[p+1]==0x11.toByte() && b[p+2]==0x11.toByte() && b[p+3]==0x11.toByte())p+=4
        return p
    }
    private fun chunk(id:Int,data:ByteArray):ByteArray=ByteArray(8+data.size).also{put32(it,0,id);put32(it,4,data.size);System.arraycopy(data,0,it,8,data.size)}
    private fun chunkAligned(id:Int,data:ByteArray,at:Int,al:Int):ByteArray{
        val start=at+8
        return chunk(id,cat(fill(align(start,al)-start,0x11),data))
    }
    private class Solid {
        var name = ""
        var hash=0; var flags=0; var verts=0; var ngroups=0; var tris=0
        var head=byteArrayOf(); var tex=byteArrayOf(); var sha=byteArrayOf()
        var groups=byteArrayOf(); var ib=byteArrayOf()
        var vb: MutableList<ByteArray> = ArrayList()
    }
    private fun unpack(block:ByteArray,usize:Int):ByteArray{
        if(isJdlz(block,0))return jdlzDecode(block)
        val magic=if(block.size>=4)latin(block,0,4)else ""
        if(magic=="RAWW"){
            if(block.size<16)throw Failure(Failure.Reason.UNSUPPORTED,"a RAWW block too short")
            val n=i32(block,8)
            if(n<0 || 16L+n>block.size)throw Failure(Failure.Reason.UNSUPPORTED,"a RAWW block's size")
            return Arrays.copyOfRange(block,16,16+n)
        }
        if(magic=="HUFF" || magic=="COMP" || magic=="LZMA")throw Failure(Failure.Reason.UNSUPPORTED,"packed as $magic")
        return Arrays.copyOf(block,minOf(usize,block.size))
    }
    private fun readModSolids(mg:ByteArray):MutableList<ByteArray>{
        val c=first(mg,0x134004) ?: throw Failure(Failure.Reason.UNSUPPORTED,"GEOMETRY.BIN has no solid table")
        val out=ArrayList<ByteArray>();var i=0
        while(i+24<=c[1]){
            val off=i32(mg,c[2]+i+4);val cs=i32(mg,c[2]+i+8);val us=i32(mg,c[2]+i+12)
            out.add(unpack(sub(mg,off,off+maxOf(cs,us)),us));i+=24
        }
        return out
    }
    private fun modPayload(d:ByteArray,o:Int,size:Int,alignment:Int,record:Int):Int{
        val p=payload(d,o,size);if(p!=o)return p
        val at=align(o,alignment)
        if(at==o || at>o+size || (o+size-at)%record!=0)return o
        for(i in o until at)if(d[i]!=0.toByte())return o
        return at
    }
    private const val MAX_GROUP_INDICES=0xFFFC
    private const val MAX_GROUP_VERTICES=0xFFF0
    private const val MAX_SOLID_VERTICES=60000
    private val REFLECTIVE=setOf(0x0000D916,0x02A05578,0x02DDDAD9,0x02EE77E1,0x05BC3A3C,0x0FEDEE40,0x12049251,0x12C9453C,0x1FBD2378,0x1FF8C329,
        0x22719FA9,0x2E65E067,0x33A26CB6,0x3ED70C43,0x471A1DCA,0x54949AFD,0x721AFDDC,0xA6348EE3.toInt(),0xB51B3ACF.toInt(),0xB57AB98E.toInt(),
        0xD6640DFF.toInt(),0xD6D6080A.toInt(),0xD79597D6.toInt(),0xDCD01662.toInt(),0xF0A43B67.toInt(),0xFD102A92.toInt(),0x78743CA1)
    private val MATTE=setOf(0x2787EDAB,0x4C95C6F8,0x52BF4C34,0x5799E60B,0x010CB64A)
    private fun reflective(shader:Int,flags:Int):Int=when{
        shader in REFLECTIVE->flags or 0x100
        shader in MATTE->flags and 0x100.inv()
        else->flags
    }
    private fun convertSolid(d:ByteArray,car:String):Solid?{
        var info:ByteArray?=null;var groups:ByteArray?=null;var idx:ByteArray?=null;var verts:ByteArray?=null
        var tex=byteArrayOf();var sha=byteArrayOf()
        for(c in walkAll(d,0,d.size)){
            val sz=c[1];val o=c[2]
            when(c[0]){
                0x134011->info=sub(d,o,o+sz)
                0x134012->tex=sub(d,o,o+sz)
                0x134013->sha=sub(d,o,o+sz)
                0x134b02->groups=sub(d,modPayload(d,o,sz,16,104),o+sz)
                0x134b03->idx=sub(d,modPayload(d,o,sz,16,2),o+sz)
                0x134b01->verts=sub(d,modPayload(d,o,sz,0x80,36),o+sz)
            }
        }
        val inf=info ?: return null
        val grp=groups ?: return null
        val ix=idx ?: return null
        val vtx=verts ?: return null
        if(inf.size<0xA0)return null
        var name=text(inf,0xA0,inf.size)
        val underscore=name.indexOf('_')
        if(underscore>0 && i32(inf,0x10)!=bhash(name) && i32(inf,0x10)==bhash(car+name.substring(underscore)))name=car+name.substring(underscore)
        val lod=if(name.isEmpty())-1 else "ABCD".indexOf(name.last())
        if(lod<0 || name.length<2 || name[name.length-2]!='_')return null
        val s=Solid()
        s.name=name.dropLast(1)+"BCDE"[lod];s.hash=bhash(s.name)
        val nIdx=ix.size/2;val gr=Buf();val ib=Buf()
        var start=0;var flagsOr=0;var totalV=0
        for(g in 0 until grp.size/104){
            val r=g*104
            var flags=i32(grp,r+0x38) and 0x1FFFF
            val shader=grp[r+0x1D].toInt() and 255
            if(shader*8+4<=sha.size)flags=reflective(i32(sha,shader*8),flags)
            val tris=i32(grp,r+0x40);val firstIdx=i32(grp,r+0x44);val count=tris*3
            var k=firstIdx;val end=minOf(firstIdx+count,nIdx)
            do{
                val remap=HashMap<Int,Int>();val b=Buf();var locals=0
                while(k+3<=end && locals+3<=MAX_GROUP_INDICES && remap.size+3<=MAX_GROUP_VERTICES){
                    for(c in 0 until 3){
                        val i=u16(ix,(k+c)*2)
                        val mapped=remap[i] ?: run{
                            val id=remap.size;remap[i]=id
                            val v=i*36
                            b.add(vtx,v,12).add(vtx,v+28,8).add(vtx,v+12,12)
                            id
                        }
                        val le=ByteArray(2);put16(le,0,mapped);ib.add(le);locals++
                    }
                    k+=3
                }
                val rec=ByteArray(96)
                System.arraycopy(grp,r,rec,0,12)
                System.arraycopy(grp,r+12,rec,16,12)
                put16(rec,0x20,locals);put16(rec,0x22,3)
                put32(rec,0x24,grp[r+0x18].toInt() and 255)
                put32(rec,0x28,flags);put32(rec,0x2C,start)
                put32(rec,0x40,grp[r+0x1D].toInt() and 255)
                gr.add(rec);s.vb.add(b.bytes());start+=locals;totalV+=remap.size;s.ngroups++
            }while(k+3<=end)
            flagsOr=flagsOr or flags
        }
        if((start and 1)!=0)ib.zeros(2)
        s.groups=gr.bytes();s.ib=ib.bytes();s.flags=flagsOr and 0xFFFF;s.verts=totalV
        s.tex=tex;s.sha=sha;s.tris=start/3
        val head=Arrays.copyOf(inf,0xA0)
        put32(head,0xC,0x400016);put32(head,0x10,s.hash);put32(head,0x14,s.tris)
        var sum=0.0
        for(a in 0..2){val e=f32(head,0x30+a*4).toDouble()-f32(head,0x20+a*4).toDouble();sum+=e*e}
        val radius=maxOf(0.001,sqrt(sum)/2)
        putF(head,0x98,radius.toFloat());putF(head,0x9C,(s.tris/radius).toFloat())
        val nm=latin(s.name+"\u0000")
        s.head=cat(head,nm,fill(align(nm.size,4)-nm.size,0))
        return s
    }
    private val LOD_SHARE=doubleArrayOf(1.0,0.12,0.04,0.015)
    private const val MAX_GEOMETRY=12L shl 20
    private const val VERTEX=32
    private fun solidBytes(s:Solid):Long{
        var n=s.ib.size.toLong()
        for(v in s.vb)n+=v.size+0x80
        return n
    }
    private fun totalBytes(solids:List<Solid>):Long=solids.sumOf{solidBytes(it)}
    private fun triangleCap(tops:List<Solid>,budget:Long):Int{
        var low=1;var high=1
        for(s in tops)high=maxOf(high,s.tris)
        while(low<high){
            val mid=(low+high+1) ushr 1
            var total=0L
            for(s in tops)total+=solidBytes(s)*minOf(s.tris,mid)/maxOf(s.tris,1)
            if(total<=budget)low=mid else high=mid-1
        }
        return low
    }
    private fun reduceLods(solids:MutableList<Solid>,log:Consumer<String>){
        val best=HashMap<String,Solid>()
        for(s in solids){
            val part=s.name.dropLast(1);val b=best[part]
            if(b==null || s.name<b.name)best[part]=s
        }
        val before=solids.sumOf{solidBytes(it)}
        for(s in solids)if(s.verts>MAX_SOLID_VERTICES)reduceSolid(s,(s.tris*MAX_SOLID_VERTICES.toDouble()/s.verts).toInt(),s===best[s.name.dropLast(1)])
        val all=LOD_SHARE.sum();val parts=best.keys.sorted();val tops=parts.mapNotNull{best[it]}
        val budget=(MAX_GEOMETRY/all).toLong()
        for(round in 0 until 4){
            if(totalBytes(tops)<=budget)break
            val cap=triangleCap(tops,budget)
            for(b in tops)reduceSolid(b,cap,true)
        }
        for(s in solids){
            val b=best[s.name.dropLast(1)]!!
            val below=s.name.last().code-b.name.last().code
            if(below>0)reduceSolid(s,(b.tris*LOD_SHARE[minOf(below,LOD_SHARE.size-1)]).toInt(),false)
        }
        for(round in 0 until 4){
            if(totalBytes(solids)<=MAX_GEOMETRY)break
            val cap=triangleCap(tops,totalBytes(tops)-(totalBytes(solids)-MAX_GEOMETRY))
            for(b in tops)reduceSolid(b,cap,true)
        }
        val after=totalBytes(solids)
        log.accept("geometry: ${before/1024} KB, to ${after/1024} KB")
    }
    private val ATTACHMENT=Pattern.compile("(.*_)(KIT\\d\\d|STYLE\\d\\d)_ATTACHMENT\\d_([A-E])")
    private fun mergeAttachments(solids:MutableList<Solid>,log:Consumer<String>):Boolean{
        val sorted=solids.sortedBy{it.name}
        val byName=HashMap<String,Solid>()
        for(x in solids)byName[x.name]=x
        var merged=0;var dropped=0
        for(part in sorted){
            val m=ATTACHMENT.matcher(part.name)
            if(!m.matches())continue
            val kind = requireNotNull(m.group(2))
            if(kind.startsWith("STYLE")){solids.remove(part);dropped++;continue}
            val target=requireNotNull(m.group(1))+kind+"_BODY_"+requireNotNull(m.group(3))
            val body=byName[target]
            if(body==null){renameSolid(part,target);byName[target]=part;merged++}
            else if(mergeSolid(body,part)||fitMerge(body,part)){solids.remove(part);merged++}
        }
        if(merged+dropped>0)log.accept("geometry: $merged attachments into bodies, $dropped left out")
        return merged+dropped>0
    }
    private fun fitMerge(body:Solid,part:Solid):Boolean{
        for(round in 0 until 4){
            val total=body.verts+part.verts
            if(total<=MAX_SOLID_VERTICES && mergeSolid(body,part))return true
            val share=MAX_SOLID_VERTICES.toDouble()/maxOf(total,1)
            reduceSolid(body,(body.tris*share).toInt(),true)
            reduceSolid(part,(part.tris*share).toInt(),true)
        }
        return mergeSolid(body,part)
    }
    private fun renameSolid(s:Solid,name:String){
        s.name=name;s.hash=bhash(name)
        val nm=latin(name+"\u0000")
        s.head=cat(Arrays.copyOf(s.head,0xA0),nm,fill(align(nm.size,4)-nm.size,0))
        put32(s.head,0x10,s.hash)
    }
    private fun tableEntry(table:Buf,entries:ByteArray,at:Int):Int{
        var i=0
        while(i+8<=table.n){
            if(i32(table.a,i)==i32(entries,at)&&i32(table.a,i+4)==i32(entries,at+4))return i/8
            i+=8
        }
        table.add(entries,at,8)
        return table.n/8-1
    }
    private fun indexCount(s:Solid):Int{
        var n=0
        for(g in 0 until s.ngroups)n+=u16(s.groups,g*96+0x20)
        return n
    }
    private fun mergeSolid(body:Solid,part:Solid):Boolean{
        val tex=Buf();val sha=Buf();tex.add(body.tex);sha.add(body.sha)
        val texAt=IntArray(part.tex.size/8){i->tableEntry(tex,part.tex,i*8)}
        val shaAt=IntArray(part.sha.size/8){i->tableEntry(sha,part.sha,i*8)}
        if(body.verts+part.verts>0xFFFF||tex.n/8>255||sha.n/8>255)return false
        val bodyIndices=indexCount(body);val partIndices=indexCount(part)
        val groups=part.groups.clone()
        for(g in 0 until part.ngroups){
            val rec=g*96
            put32(groups,rec+0x24,texAt[i32(groups,rec+0x24) and 255])
            put32(groups,rec+0x2C,i32(groups,rec+0x2C)+bodyIndices)
            put32(groups,rec+0x40,shaAt[i32(groups,rec+0x40) and 255])
        }
        val ib=Buf()
        ib.add(body.ib,0,bodyIndices*2).add(part.ib,0,partIndices*2)
        if(((bodyIndices+partIndices) and 1)!=0)ib.zeros(2)
        body.ib=ib.bytes();body.groups=cat(body.groups,groups);body.vb.addAll(part.vb)
        body.ngroups+=part.ngroups;body.verts+=part.verts;body.tris+=part.tris
        body.flags=body.flags or part.flags;body.tex=tex.bytes();body.sha=sha.bytes()
        body.head[0x19]=(tex.n/8).toByte();body.head[0x1A]=(sha.n/8).toByte()
        put32(body.head,0x14,body.tris)
        var sum=0.0
        for(a in 0..2){
            val low=minOf(f32(body.head,0x20+a*4),f32(part.head,0x20+a*4))
            val high=maxOf(f32(body.head,0x30+a*4),f32(part.head,0x30+a*4))
            putF(body.head,0x20+a*4,low);putF(body.head,0x30+a*4,high)
            val e=high.toDouble()-low.toDouble();sum+=e*e
        }
        val radius=maxOf(0.001,sqrt(sum)/2)
        putF(body.head,0x98,radius.toFloat());putF(body.head,0x9C,(body.tris/radius).toFloat())
        return true
    }
    private const val WHEEL_BIN=0.02
    private val NOT_BODY=Pattern.compile(".*(TIRE|BRAKE|WINDOW|INTERIOR|DRIVER|DAMAGE|DECAL|MIRROR|SPOILER|HOOD|GLASS|LIGHT).*")
    private class WheelPlaces{
        var frontX=0.0;var rearX=0.0;var frontY=0.0;var rearY=0.0;var radius=0.0;var width=0.0
    }
    private fun solidTriangles(s:Solid):MutableList<DoubleArray>{
        val out=ArrayList<DoubleArray>()
        for(g in 0 until s.ngroups){
            val count=u16(s.groups,g*96+0x20);val start=i32(s.groups,g*96+0x2C)
            val v=s.vb[g];var t=0
            while(t+3<=count){
                val tri=DoubleArray(9)
                for(c in 0..2){
                    val o=u16(s.ib,(start+t+c)*2)*VERTEX
                    for(a in 0..2)tri[c*3+a]=f32(v,o+a*4).toDouble()
                }
                out.add(tri);t+=3
            }
        }
        return out
    }
    private fun measureWheels(solids:List<Solid>):WheelPlaces?{
        val tris=ArrayList<DoubleArray>();var tire:DoubleArray?=null
        for(s in solids.sortedBy{it.name}){
            if(s.name.endsWith("_FRONT_TIRE_B")){
                tire=doubleArrayOf(Double.MAX_VALUE,-Double.MAX_VALUE,Double.MAX_VALUE,-Double.MAX_VALUE)
                for(t in solidTriangles(s))for(c in 0..2){
                    tire[0]=minOf(tire[0],t[c*3+1]);tire[1]=maxOf(tire[1],t[c*3+1])
                    tire[2]=minOf(tire[2],t[c*3+2]);tire[3]=maxOf(tire[3],t[c*3+2])
                }
            }
            if(s.name.endsWith("_B") && (s.name.contains("_BASE_")||s.name.contains("_KIT00_")) && !NOT_BODY.matcher(s.name).matches())tris.addAll(solidTriangles(s))
        }
        val tr=tire ?: return null
        if(tris.isEmpty())return null
        var x0=Double.MAX_VALUE;var x1=-Double.MAX_VALUE;var half=0.0
        var zLow=Double.MAX_VALUE;var zTop=-Double.MAX_VALUE
        for(t in tris)for(c in 0..2){
            x0=minOf(x0,t[c*3]);x1=maxOf(x1,t[c*3]);half=maxOf(half,abs(t[c*3+1]));zLow=minOf(zLow,t[c*3+2]);zTop=maxOf(zTop,t[c*3+2])
        }
        val bins=((x1-x0)/WHEEL_BIN).toInt()+2
        val bottom=DoubleArray(bins){Double.NaN}
        for(t in tris){
            if(minOf(abs(t[1]),abs(t[4]),abs(t[7]))<half*0.7)continue
            val ax=t[3]-t[0];val ay=t[4]-t[1];val az=t[5]-t[2]
            val bx=t[6]-t[0];val by=t[7]-t[1];val bz=t[8]-t[2]
            val nx=ay*bz-az*by;val ny=az*bx-ax*bz;val nz=ax*by-ay*bx
            val nl=sqrt(nx*nx+ny*ny+nz*nz)
            if(nl==0.0||abs(ny)/nl<0.5)continue
            for(e in 0..2){
                val a=e*3;val b=(e+1)%3*3
                val steps=(abs(t[a]-t[b])/WHEEL_BIN).toInt()+1
                for(i in 0..steps){
                    val f=i.toDouble()/steps;val x=t[a]+(t[b]-t[a])*f;val z=t[a+2]+(t[b+2]-t[a+2])*f
                    val k=((x-x0)/WHEEL_BIN).toInt()
                    if(bottom[k].isNaN()||z<bottom[k])bottom[k]=z
                }
            }
        }
        val length=x1-x0;val known=bottom.filter{!it.isNaN()}.sorted()
        if(known.isEmpty())return null
        val limit=known[known.size/4]+(zTop-zLow)*0.12;val middle=(x0+x1)/2
        var front:DoubleArray?=null;var rear:DoubleArray?=null;var start=-1
        for(k in 0..bins){
            val x=x0+k*WHEEL_BIN
            val high=k<bins && x>x0+length*0.04 && x<x1-length*0.04 && (bottom[k].isNaN()||bottom[k]>limit)
            if(high && start<0)start=k
            else if(!high && start>=0){
                if((k-start)*WHEEL_BIN>length*0.08){
                    val run=doubleArrayOf(x0+start*WHEEL_BIN,x0+k*WHEEL_BIN)
                    if((run[0]+run[1])/2>middle){
                        if(front==null||run[1]-run[0]>front[1]-front[0])front=run
                    }else if(rear==null||run[1]-run[0]>rear[1]-rear[0])rear=run
                }
                start=-1
            }
        }
        val fr=front ?: return null;val re=rear ?: return null
        var frontHalf=0.0;var rearHalf=0.0
        for(t in tris)for(c in 0..2){
            val x=t[c*3];val y=abs(t[c*3+1])
            if(x>=fr[0]&&x<=fr[1])frontHalf=maxOf(frontHalf,y)
            if(x>=re[0]&&x<=re[1])rearHalf=maxOf(rearHalf,y)
        }
        return WheelPlaces().apply{
            frontX=(fr[0]+fr[1])/2;rearX=(re[0]+re[1])/2
            frontY=frontHalf+minOf(tr[0],0.0);rearY=rearHalf+minOf(tr[0],0.0)
            radius=(tr[3]-tr[2])/2;width=tr[1]-tr[0]
        }.takeIf{it.frontY>=0.4&&it.rearY>=0.4&&it.radius>=0.1&&it.radius<=0.7}
    }
    private fun readBuiltSolids(g:ByteArray):MutableList<Solid>{
        val out=ArrayList<Solid>();var s:Solid?=null
        for(c in walkAll(g,0,g.size)){
            val o=c[2];val end=o+c[1]
            if(c[0]==0x134011){
                s=Solid();s.head=sub(g,o,end);s.name=text(s.head,0xA0,s.head.size)
                s.hash=i32(s.head,0x10);s.tris=i32(s.head,0x14)
                s.tex=byteArrayOf();s.sha=byteArrayOf();out.add(s)
            }else if(s!=null){
                val p=payload(g,o,c[1])
                when(c[0]){
                    0x134012->s.tex=sub(g,o,end)
                    0x134013->s.sha=sub(g,o,end)
                    0x134B00->{s.flags=u16(g,p+4);s.verts=u16(g,p+6);s.ngroups=u16(g,p+8)}
                    0x134B02->s.groups=sub(g,p,end)
                    0x134B03->s.ib=sub(g,p,end)
                    0x134B01->s.vb.add(sub(g,p,end))
                }
            }
        }
        return out
    }
    private fun relight(g:ByteArray):Boolean{
        var changed=false;var sha:ByteArray?=null;var mesh=-1
        for(c in walkAll(g,0,g.size)){
            val o=c[2];val end=o+c[1]
            when(c[0]){
                0x134011->{sha=null;mesh=-1}
                0x134013->sha=sub(g,o,end)
                0x134B00->mesh=payload(g,o,c[1])
                0x134B02->{
                    val sh=sha ?: continue
                    var or=0;var p=payload(g,o,c[1])
                    while(p+96<=end){
                        val shader=g[p+0x40].toInt() and 255
                        val was=i32(g,p+0x28)
                        val now=if(shader*8+4<=sh.size)reflective(i32(sh,shader*8),was) else was
                        if(now!=was){put32(g,p+0x28,now);changed=true}
                        or=or or now;p+=96
                    }
                    if(mesh>=0 && (u16(g,mesh+4) or (or and 0xFFFF))!=u16(g,mesh+4)){
                        put16(g,mesh+4,u16(g,mesh+4) or (or and 0xFFFF));changed=true
                    }
                }
            }
        }
        return changed
    }
    @JvmStatic
    @Throws(Failure::class, IOException::class)
    fun fitGeometry(disc:Disc,geometry:File,log:Consumer<String>){
        val g=Files.readAllBytes(geometry.toPath())
        if(relight(g)){
            val temp=File(geometry.path+".new")
            Files.write(temp.toPath(),g)
            Files.move(temp.toPath(),geometry.toPath(),StandardCopyOption.REPLACE_EXISTING)
            log.accept((geometry.parentFile?.name ?: geometry.name)+": reflection flags set by shader")
        }
        val solids=readBuiltSolids(g)
        if(solids.isEmpty())return
        for(s in solids)if(s.groups.isEmpty()||s.ib.isEmpty()||s.vb.size!=s.ngroups||s.groups.size!=s.ngroups*96)return
        val merged=mergeAttachments(solids,log)
        if(!merged && geometry.length()<=MAX_GEOMETRY+(64L shl 10))return
        reduceLods(solids,log)
        val temp=File(geometry.path+".new")
        Files.write(temp.toPath(),buildGeometry(solids,disc.load("CARS\\GALLARDO\\GEOMETRY.BIN")))
        Files.move(temp.toPath(),geometry.toPath(),StandardCopyOption.REPLACE_EXISTING)
    }
    private data class Cluster(val groups:ByteArray,val vb:MutableList<ByteArray>,val ib:ByteArray,val verts:Int,val tris:Int)
    private fun clusterToward(s:Solid,goal:Int){
        if(s.tris<=goal)return
        var found:Cluster?=null;var low=2;var high=1024
        while(low<=high){
            val mid=(low+high) ushr 1
            val r=clusterSolid(s,mid)
            if(r.tris<=goal){found=r;low=mid+1}else high=mid-1
        }
        val result=found ?: clusterSolid(s,2)
        applyMesh(s,result.groups,result.vb,result.ib,result.verts,result.tris)
    }
    private fun applyMesh(s:Solid,groups:ByteArray,vb:MutableList<ByteArray>,ib:ByteArray,verts:Int,tris:Int){
        s.groups=groups;s.vb=vb;s.ib=ib;s.verts=verts;s.tris=tris
        put32(s.head,0x14,s.tris)
        putF(s.head,0x9C,(s.tris/f32(s.head,0x98).toDouble()).toFloat())
    }
    private fun clusterSolid(s:Solid,grid:Int):Cluster{
        val min=doubleArrayOf(Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE)
        val max=doubleArrayOf(-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE)
        for(v in s.vb){
            var o=0
            while(o+VERTEX<=v.size){
                for(a in 0..2){val p=f32(v,o+a*4).toDouble();if(p<min[a])min[a]=p;if(p>max[a])max[a]=p}
                o+=VERTEX
            }
        }
        val extent=maxOf(max[0]-min[0],max[1]-min[1],max[2]-min[2])
        val cell=if(extent>0)extent/grid else 1.0
        val groups=s.groups.clone();val vbs=ArrayList<ByteArray>();val ib=Buf();val le=ByteArray(2)
        var start=0;var verts=0
        for(g in 0 until s.ngroups){
            val rec=g*96;val count=u16(s.groups,rec+0x20);val first=i32(s.groups,rec+0x2C)
            val v=s.vb[g];val ids=HashMap<Long,Int>();val nv=Buf();var kept=0;val keys=LongArray(3)
            var t=0
            while(t+3<=count){
                for(c in 0..2){
                    val o=u16(s.ib,(first+t+c)*2)*VERTEX
                    var key=0L
                    for(a in 0..2)key=key or (((f32(v,o+a*4)-min[a])/cell).toLong() shl (a*20))
                    for(a in 0..2)if(f32(v,o+20+a*4)<0)key=key or (1L shl (60+a))
                    keys[c]=key
                }
                if(keys[0]!=keys[1]&&keys[1]!=keys[2]&&keys[0]!=keys[2]){
                    for(c in 0..2){
                        val id=ids[keys[c]] ?: run{
                            val next=ids.size;ids[keys[c]]=next
                            nv.add(v,u16(s.ib,(first+t+c)*2)*VERTEX,VERTEX)
                            next
                        }
                        put16(le,0,id);ib.add(le)
                    }
                    kept+=3
                }
                t+=3
            }
            if(kept==0&&count>=3){
                for(c in 0..2){nv.add(v,u16(s.ib,(first+c)*2)*VERTEX,VERTEX);put16(le,0,c);ib.add(le)}
                kept=3;verts+=3
            }
            put16(groups,rec+0x20,kept);put32(groups,rec+0x2C,start)
            vbs.add(nv.bytes());start+=kept;verts+=ids.size
        }
        if((start and 1)!=0)ib.zeros(2)
        return Cluster(groups,vbs,ib.bytes(),verts,start/3)
    }
    private fun reduceSolid(s:Solid,target0:Int,best:Boolean){
        val target=maxOf(target0,1)
        if(s.tris<=target)return
        if(best){
            collapseToward(s,target)
            if(s.tris>target+target/4)collapseToward(s,target)
            return
        }
        clusterToward(s,target*2);collapseToward(s,target)
        if(s.tris>target+target/4)clusterToward(s,target)
    }
    private fun collapseToward(s:Solid,target:Int){
        if(s.tris<=target)return
        val share=target.toDouble()/s.tris
        val groups=s.groups.clone();val vb=ArrayList<ByteArray>();val ib=Buf()
        var start=0;var verts=0
        for(g in 0 until s.ngroups){
            val rec=g*96;val count=u16(s.groups,rec+0x20);val first=i32(s.groups,rec+0x2C)
            val tri=IntArray(count-count%3){i->u16(s.ib,(first+i)*2)}
            val d=Decimator(s.vb[g],tri)
            d.run(maxOf(1,(tri.size/3*share).toInt()))
            val nv=Buf()
            val kept=d.emit(s.vb[g],nv,ib)
            put16(groups,rec+0x20,kept);put32(groups,rec+0x2C,start)
            vb.add(nv.bytes());verts+=nv.n/VERTEX;start+=kept
        }
        if((start and 1)!=0)ib.zeros(2)
        applyMesh(s,groups,vb,ib.bytes(),verts,start/3)
    }
    private class Decimator(v:ByteArray,val tri:IntArray){
        val n=v.size/VERTEX
        val pos=DoubleArray(n*3);val normal=DoubleArray(n*3);val uv=DoubleArray(n*2);val q=DoubleArray(n*10)
        val version=IntArray(n);val deadTri=BooleanArray(tri.size/3);val deadVert=BooleanArray(n);val border=BooleanArray(n)
        val adj:MutableList<MutableList<Int>> = ArrayList()
        val borderEdges=HashSet<Long>()
        var scale=0.0;var live=tri.size/3
        init{
            for(i in 0 until n){
                val o=i*VERTEX
                for(a in 0..2){pos[i*3+a]=f32(v,o+a*4).toDouble();normal[i*3+a]=f32(v,o+20+a*4).toDouble()}
                uv[i*2]=f32(v,o+12).toDouble();uv[i*2+1]=f32(v,o+16).toDouble()
                adj.add(ArrayList())
            }
            val edges=HashMap<Long,Int>()
            var area=0.0;var edge=0.0
            for(t in 0 until live){
                val a=tri[t*3];val b=tri[t*3+1];val c=tri[t*3+2]
                adj[a].add(t);adj[b].add(t);adj[c].add(t)
                for(k in longArrayOf(key(a,b),key(b,c),key(c,a)))edges[k]=(edges[k]?:0)+1
                edge+=dist2(a,b)+dist2(b,c)+dist2(c,a)
                val m=cross(a,b,c)
                val len=sqrt(m[0]*m[0]+m[1]*m[1]+m[2]*m[2])
                if(len<=0)continue
                area+=len/2
                val x=m[0]/len;val y=m[1]/len;val z=m[2]/len
                val w=-(x*pos[a*3]+y*pos[a*3+1]+z*pos[a*3+2])
                val plane=doubleArrayOf(x*x,x*y,x*z,x*w,y*y,y*z,y*w,z*z,z*w,w*w)
                for(k in 0 until 10){
                    val add=plane[k]*(len/2)
                    q[a*10+k]+=add;q[b*10+k]+=add;q[c*10+k]+=add
                }
            }
            for((k,value) in edges){
                if(value!=1)continue
                borderEdges.add(k)
                border[(k ushr 32).toInt()]=true
                border[k.toInt()]=true
            }
            val count=maxOf(live,1)
            scale=area/count*(edge/(3.0*count))
            if(scale<=0)scale=1.0
        }
        private fun key(a:Int,b:Int):Long = if(a<b)(a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
        private fun dist2(a:Int,b:Int):Double{
            val x=pos[a*3]-pos[b*3];val y=pos[a*3+1]-pos[b*3+1];val z=pos[a*3+2]-pos[b*3+2]
            return x*x+y*y+z*z
        }
        private fun cross(a:Int,b:Int,c:Int):DoubleArray{
            val ux=pos[b*3]-pos[a*3];val uy=pos[b*3+1]-pos[a*3+1];val uz=pos[b*3+2]-pos[a*3+2]
            val vx=pos[c*3]-pos[a*3];val vy=pos[c*3+1]-pos[a*3+1];val vz=pos[c*3+2]-pos[a*3+2]
            return doubleArrayOf(uy*vz-uz*vy,uz*vx-ux*vz,ux*vy-uy*vx)
        }
        private fun cost(u:Int,v:Int):Double{
            val x=pos[v*3];val y=pos[v*3+1];val z=pos[v*3+2]
            val a=u*10;val b=v*10
            var e=(q[a]+q[b])*x*x+2*(q[a+1]+q[b+1])*x*y+2*(q[a+2]+q[b+2])*x*z+
                2*(q[a+3]+q[b+3])*x+(q[a+4]+q[b+4])*y*y+2*(q[a+5]+q[b+5])*y*z+
                2*(q[a+6]+q[b+6])*y+(q[a+7]+q[b+7])*z*z+2*(q[a+8]+q[b+8])*z+(q[a+9]+q[b+9])
            val du=uv[u*2]-uv[v*2];val dv=uv[u*2+1]-uv[v*2+1]
            val dot=normal[u*3]*normal[v*3]+normal[u*3+1]*normal[v*3+1]+normal[u*3+2]*normal[v*3+2]
            e+=scale*(0.1*minOf(du*du+dv*dv,1.0)+0.1*maxOf(1.0-dot,0.0))
            if(border[u])e+=scale
            return e
        }
        private fun neighbours(u:Int):java.util.TreeSet<Int>{
            val out=java.util.TreeSet<Int>()
            for(t in adj[u]){
                if(deadTri[t])continue
                for(c in 0..2)if(tri[t*3+c]!=u)out.add(tri[t*3+c])
            }
            return out
        }
        private fun can(u:Int,v:Int,seams:Boolean):Boolean{
            if(border[u]&&(!seams||!borderEdges.contains(key(u,v))))return false
            val shared=neighbours(u);shared.retainAll(neighbours(v))
            if(shared.size>2)return false
            for(t in adj[u]){
                if(deadTri[t])continue
                val a=tri[t*3];val b=tri[t*3+1];val c=tri[t*3+2]
                if(a==v||b==v||c==v)continue
                val before=cross(a,b,c)
                val after=cross(if(a==u)v else a,if(b==u)v else b,if(c==u)v else c)
                val bb=before[0]*before[0]+before[1]*before[1]+before[2]*before[2]
                val aa=after[0]*after[0]+after[1]*after[1]+after[2]*after[2]
                if(aa<=1e-24)return false
                val dot=before[0]*after[0]+before[1]*after[1]+before[2]*after[2]
                if(dot<=0||dot*dot<0.04*aa*bb)return false
            }
            return true
        }
        private fun push(heap:java.util.PriorityQueue<DoubleArray>,u:Int,v:Int){
            heap.add(doubleArrayOf(cost(u,v),u.toDouble(),v.toDouble(),version[u].toDouble(),version[v].toDouble()))
        }
        fun run(target:Int){
            for(pass in 0..1){
                if(live<=target)break
                val seams=pass==1
                val heap=java.util.PriorityQueue<DoubleArray>{x,y->
                    var c=java.lang.Double.compare(x[0],y[0])
                    if(c!=0)c else{
                        c=java.lang.Double.compare(x[1],y[1]);if(c!=0)c else java.lang.Double.compare(x[2],y[2])
                    }
                }
                for(u in 0 until n)for(v in neighbours(u))push(heap,u,v)
                while(live>target && heap.isNotEmpty()){
                    val e=heap.remove();val u=e[1].toInt();val v=e[2].toInt()
                    if(deadVert[u]||deadVert[v]||version[u]!=e[3].toInt()||version[v]!=e[4].toInt()||!can(u,v,seams))continue
                    collapse(u,v)
                    for(w in neighbours(v)){push(heap,v,w);push(heap,w,v)}
                }
            }
        }
        private fun collapse(u:Int,v:Int){
            for(w in neighbours(u))if(w!=v&&borderEdges.contains(key(u,w)))borderEdges.add(key(v,w))
            if(border[u])border[v]=true
            for(t in adj[u]){
                if(deadTri[t])continue
                val a=tri[t*3];val b=tri[t*3+1];val c=tri[t*3+2]
                if(a==v||b==v||c==v){deadTri[t]=true;live--;continue}
                for(k in 0..2)if(tri[t*3+k]==u)tri[t*3+k]=v
                adj[v].add(t)
            }
            for(k in 0 until 10)q[v*10+k]+=q[u*10+k]
            deadVert[u]=true;version[v]++
        }
        fun emit(v:ByteArray,nv:Buf,ib:Buf):Int{
            val ids=HashMap<Int,Int>();val le=ByteArray(2);var kept=0
            for(t in deadTri.indices){
                if(deadTri[t])continue
                for(c in 0..2){
                    val i=tri[t*3+c]
                    val id=ids[i] ?: run{val next=ids.size;ids[i]=next;nv.add(v,i*VERTEX,VERTEX);next}
                    put16(le,0,id);ib.add(le)
                }
                kept+=3
            }
            return kept
        }
    }
    private fun buildSolid(s:Solid,at:Int):ByteArray{
        val head=cat(chunk(0x134011,s.head),chunk(0x134012,s.tex),chunk(0x134013,s.sha))
        val p=at+8+head.size+8
        val mesh=Buf();val hdr=ByteArray(12+40)
        put32(hdr,0,0xF);put16(hdr,4,s.flags);put16(hdr,6,s.verts);put16(hdr,8,s.ngroups);put16(hdr,10,0x20)
        mesh.add(chunkAligned(0x134B00,hdr,p,16))
        mesh.add(chunkAligned(0x134B02,s.groups,p+mesh.n,16))
        mesh.add(chunkAligned(0x134B03,s.ib,p+mesh.n,16))
        for(v in s.vb)mesh.add(chunkAligned(0x134B01,v,p+mesh.n,0x80))
        return chunk(0x80134010.toInt(),cat(head,chunk(0x80134100.toInt(),mesh.bytes())))
    }
    private fun buildGeometry(solids:MutableList<Solid>,template:ByteArray):ByteArray{
        val tc=first(template,0x134002) ?: throw Failure(Failure.Reason.UNSUPPORTED,"the game's geometry has no table")
        val info=sub(template,tc[2],tc[2]+tc[1]);val tableSize=8+144+8+solids.size*8+8+solids.size*24+8
        val firstAt=8+8+8+tableSize;val at=align(firstAt+8,0x80)
        val sorted=solids.sortedWith(compareBy{it.hash.toLong() and 0xFFFFFFFFL})
        val body=Buf();val hashes=Buf();val offs=Buf()
        for(s in sorted){
            if(((at+body.n) and 0x7F)!=0){
                val pad=Math.floorMod(0x80-(at+body.n+8)%0x80,0x80)
                body.add(chunk(0,ByteArray(pad)))
            }
            val off=at+body.n
            val c=buildSolid(s,off)
            hashes.add(ints(s.hash,0));offs.add(ints(s.hash,off,c.size,c.size,0,0));body.add(c)
        }
        val size=align(at+body.n,0x80)
        put32(info,0xC,solids.size);put32(info,0x68,size)
        val table=cat(chunk(0x134002,info),chunk(0x134003,hashes.bytes()),chunk(0x134004,offs.bytes()),chunk(0x80134008.toInt(),byteArrayOf()))
        val head=cat(chunk(0,byteArrayOf()),chunk(0x80134001.toInt(),table))
        val gap=at-(8+head.size)-8
        val out=chunk(0x80134000.toInt(),cat(head,chunk(0,fill(gap,0)),body.bytes()))
        return if(out.size<size)Arrays.copyOf(out,size)else out
    }
    private class Texture{
        var hash=0;var w=0;var h=0
        var name="";var fmt=""
        var px=byteArrayOf()
    }
    private fun xboxFormat(fmt:String):Int=when(fmt){"DXT1"->0x0C;"DXT3"->0x0E;"DXT5"->0x0F;else->-1}
    private fun blockSize(fmt:String)=if(fmt=="DXT1")8 else 16
    private fun rgb565(c:Int):DoubleArray{
        val r=(c shr 11) and 31;val g=(c shr 5) and 63;val b=c and 31
        return doubleArrayOf(((r shl 3) or (r shr 2)).toDouble(),((g shl 2) or (g shr 4)).toDouble(),((b shl 3) or (b shr 2)).toDouble())
    }
    private fun to565(r:Double,g:Double,b:Double):Int=((r.toInt() shr 3) shl 11) or ((g.toInt() shr 2) shl 5) or (b.toInt() shr 3)
    private fun blend(a:DoubleArray,b:DoubleArray,wa:Double,wb:Double,div:Double):DoubleArray=
        doubleArrayOf((wa*a[0]+wb*b[0])/div,(wa*a[1]+wb*b[1])/div,(wa*a[2]+wb*b[2])/div)
    private fun blockQuarters(px:ByteArray,cb:Int,fourColour:Boolean):Array<DoubleArray>{
        val c0=u16(px,cb);val c1=u16(px,cb+2);val bits=i32(px,cb+4)
        val a=rgb565(c0);val b=rgb565(c1)
        val pal:Array<DoubleArray>
        val clear:Int
        if(fourColour||c0>c1){
            pal=arrayOf(a,b,blend(a,b,2.0,1.0,3.0),blend(a,b,1.0,2.0,3.0));clear=4
        }else{
            pal=arrayOf(a,b,blend(a,b,1.0,1.0,2.0),doubleArrayOf(0.0,0.0,0.0));clear=3
        }
        val out=Array(4){DoubleArray(0)};var q=0
        for(qy in 0..2 step 2)for(qx in 0..2 step 2){
            var r=0.0;var g=0.0;var bl=0.0;var n=0
            for(py in qy until qy+2)for(x in qx until qx+2){
                val i=(bits ushr (2*(py*4+x))) and 3
                r+=pal[i][0];g+=pal[i][1];bl+=pal[i][2]
                if(i!=clear)n++
            }
            out[q++]=doubleArrayOf(r/4,g/4,bl/4,n.toDouble())
        }
        return out
    }
    private fun shrink(t:Texture){
        val bs=blockSize(t.fmt);val px=t.px
        val ow=maxOf(1,(t.w+3)/4);val oh=maxOf(1,(t.h+3)/4)
        val nw=maxOf(4,t.w/2);val nh=maxOf(4,t.h/2)
        val bw=(nw+3)/4;val bh=(nh+3)/4
        val out=ByteArray(bw*bh*bs)
        val cols=Array(16){DoubleArray(0)};val alpha=IntArray(16)
        for(y in 0 until bh)for(x in 0 until bw){
            for(sy in 0..1)for(sx in 0..1){
                val gy=minOf(y*2+sy,oh-1);val gx=minOf(x*2+sx,ow-1)
                val src=(gy*ow+gx)*bs
                val q=blockQuarters(px,src+bs-8,bs==16)
                for(i in 0 until 4){
                    val at=(sy*2+(i shr 1))*4+sx*2+(i and 1)
                    cols[at]=q[i]
                    if(bs==16)alpha[at]=alphaQuarter(px,src,i)
                }
            }
            val at=(y*bw+x)*bs
            if(bs==16)for(r in 0 until 4)put16(out,at+r*2,alpha[r*4] or (alpha[r*4+1] shl 4) or (alpha[r*4+2] shl 8) or (alpha[r*4+3] shl 12))
            packBlock(out,at+bs-8,cols,bs)
        }
        t.px=out;t.w=nw;t.h=nh
    }
    private fun alphaQuarter(px:ByteArray,a:Int,q:Int):Int{
        val qy=(q shr 1)*2;val qx=(q and 1)*2
        var sum=0
        for(y in qy until qy+2){
            val row=u16(px,a+y*2)
            for(x in qx until qx+2)sum+=(row shr (4*x)) and 15
        }
        return sum/4
    }
    private fun packBlock(out:ByteArray,cb:Int,cols:Array<DoubleArray>,bs:Int){
        var hi:DoubleArray?=null;var lo:DoubleArray?=null
        var hiV=0.0;var loV=0.0;var seen=0
        for(c in cols){
            if(c[3]<2 && bs!=16)continue
            seen++
            val v=c[0]+c[1]+c[2]
            if(hi==null||v>hiV){hi=c;hiV=v}
            if(lo==null||v<loV){lo=c;loV=v}
        }
        if(seen==0){put16(out,cb,0);put16(out,cb+2,0);put32(out,cb+4,-1);return}
        val clear=bs==8&&seen<16
        var cHi=to565(hi!![0],hi[1],hi[2]);var cLo=to565(lo!![0],lo[1],lo[2])
        val c0:Int;val c1:Int;val pal:Array<DoubleArray>
        if(clear){
            c0=minOf(cHi,cLo);c1=maxOf(cHi,cLo)
            val a=rgb565(c0);val b=rgb565(c1)
            pal=arrayOf(a,b,blend(a,b,1.0,1.0,2.0))
        }else{
            if(cHi<cLo){val t=cHi;cHi=cLo;cLo=t}
            if(cHi==cLo){put16(out,cb,cHi);put16(out,cb+2,cLo);put32(out,cb+4,0);return}
            c0=cHi;c1=cLo
            val a=rgb565(c0);val b=rgb565(c1)
            pal=arrayOf(a,b,blend(a,b,2.0,1.0,3.0),blend(a,b,1.0,2.0,3.0))
        }
        var bits=0
        for(i in 0 until 16){
            val c=cols[i];var v=0
            if(clear&&c[3]<2)v=3
            else{
                var best=Double.MAX_VALUE
                for(k in pal.indices){
                    val dr=c[0]-pal[k][0];val dg=c[1]-pal[k][1];val db=c[2]-pal[k][2]
                    val e=dr*dr+dg*dg+db*db
                    if(e<best){best=e;v=k}
                }
            }
            bits=bits or (v shl (2*i))
        }
        put16(out,cb,c0);put16(out,cb+2,c1);put32(out,cb+4,bits)
    }
    private val DXT=Pattern.compile("DXT[1-5]")
    private fun readModTextures(mt:ByteArray):MutableList<Texture>{
        var table:IntArray?=null
        for(c in walkAll(mt,0,mt.size))if(c[0]==0x33310003)table=c
        val tab=table ?: throw Failure(Failure.Reason.UNSUPPORTED,"TEXTURES.BIN is not a compressed pack")
        val out=ArrayList<Texture>();var i=0
        while(i+24<=tab[1]){
            val h=i32(mt,tab[2]+i);val off=i32(mt,tab[2]+i+4)
            val cs=i32(mt,tab[2]+i+8);val us=i32(mt,tab[2]+i+12)
            val d=unpack(sub(mt,off,off+cs),us)
            val at=rfind(d,ints(h))
            if(at<24)throw Failure(Failure.Reason.UNSUPPORTED,"a texture has no record")
            val t=Texture();t.hash=h;t.name=text(d,at-24,at)
            t.w=u16(d,at+0x20);t.h=u16(d,at+0x22)
            val m=DXT.matcher(latin(d,at,d.size-at))
            if(!m.find()){i+=24;continue}
            t.fmt=m.group()
            if(xboxFormat(t.fmt)<0)throw Failure(Failure.Reason.UNSUPPORTED,"${t.name} is ${t.fmt}")
            t.px=Arrays.copyOf(d,((t.w+3)/4)*((t.h+3)/4)*blockSize(t.fmt))
            out.add(t);i+=24
        }
        return out
    }
    private fun shrinkTextures(tex:MutableList<Texture>,limit:Int,log:Consumer<String>){
        val before=total(tex)
        while(total(tex)>limit){
            var big=tex[0]
            for(t in tex)if(t.px.size>big.px.size)big=t
            if(big.w<=32||big.h<=32)break
            shrink(big)
        }
        log.accept("textures: ${tex.size}, ${before/1024} KB, to ${total(tex)/1024} KB")
    }
    private fun total(tex:List<Texture>):Long=tex.sumOf{it.px.size.toLong()}
    private fun ilog2(n:Int):Int{
        var r=0;while((1 shl r)<n)r++
        return r
    }
    private fun templateRecords(xt:ByteArray):MutableMap<String,ByteArray>{
        var table:IntArray?=null
        for(c in walkAll(xt,0,xt.size))if(c[0]==0x33310003)table=c
        val tpl=HashMap<String,ByteArray>()
        val tab=table
        if(tab!=null){
            var i=0
            while(i+24<=tab[1] && tpl.size<2){
                val off=i32(xt,tab[2]+i+4);val cs=i32(xt,tab[2]+i+8)
                if(!isJdlz(xt,off)){i+=24;continue}
                val d=jdlzDecode(sub(xt,off,off+cs))
                val rec=sub(d,maxOf(0,d.size-172),d.size)
                if("DXT1" !in tpl&&find(rec,ints(0x302,0x303,0x0C),0)>=0)tpl["DXT1"]=rec
                if("DXT3" !in tpl&&find(rec,ints(0x302,0x303,0x0E),0)>=0)tpl["DXT3"]=rec
                i+=24
            }
        }
        if(tpl.size<2)throw Failure(Failure.Reason.UNSUPPORTED,"no Xbox texture record to copy from")
        val five=tpl["DXT3"]!!.clone()
        put32(five,find(five,ints(0x302,0x303,0x0E),0)+8,0x0F)
        tpl["DXT5"]=five
        return tpl
    }
    private fun buildTextures(tex:List<Texture>,xt:ByteArray,car:String):ByteArray{
        val tpl=templateRecords(xt)
        val ci=first(xt,0x33310001);val ch=first(xt,0x33320001)
        if(ci==null||ch==null)throw Failure(Failure.Reason.UNSUPPORTED,"the game's texture pack has no head")
        val tInfo=sub(xt,ci[2],ci[2]+ci[1]);val tHead=sub(xt,ch[2],ch[2]+ch[1])
        val path="Global\\Pipeline\\CarTemplateTextures_$car.tpk"
        val nameBytes=Arrays.copyOf(latin(path),0x40)
        System.arraycopy(nameBytes,0,tInfo,0x20,0x40)
        put32(tInfo,0x60,bhash(path));put32(tHead,12,bhash(path))
        val sorted=tex.sortedWith(compareBy{it.hash.toLong() and 0xFFFFFFFFL})
        val blocks=ArrayList<ByteArray>();val hashes=Buf();var offset=0
        for(t in sorted){
            val rec=tpl[t.fmt]!!.clone();val nm=latin(t.name)
            Arrays.fill(rec,0,24,0.toByte())
            System.arraycopy(nm,0,rec,0,minOf(nm.size,23))
            put32(rec,24,t.hash)
            val n=t.px.size
            put32(rec,24+0xC,offset);put32(rec,24+0x10,offset+n)
            put32(rec,24+0x14,n);put32(rec,24+0x18,0);put32(rec,24+0x1C,n)
            put16(rec,24+0x20,t.w);put16(rec,24+0x22,t.h)
            rec[24+0x24]=ilog2(t.w).toByte();rec[24+0x25]=ilog2(t.h).toByte()
            val pad=Math.floorMod(8-(n+rec.size),16)
            blocks.add(cat(t.px,ByteArray(pad),rec));hashes.add(ints(t.hash,0))
            offset=align(offset+n,0x80)
        }
        val sized=textureHead(tInfo,tHead,hashes.bytes(),ByteArray(sorted.size*24))
        val dataChunk=8+sized[0].size+8+sized[1].size+8
        val dataAt=align(dataChunk,0x80)
        val data=Buf(fill(dataAt-dataChunk,0x11));val rows=Buf()
        for(i in blocks.indices){
            val block=blocks[i];val z=jdlzEncode(block)
            if(!jdlzDecode(z).contentEquals(block))throw IllegalStateException("JDLZ encoder failed its round trip")
            rows.add(ints(sorted[i].hash,dataChunk+data.n,z.size,block.size,0x100,0))
            data.add(z);data.zeros(align(dataChunk+data.n,16)-dataChunk-data.n)
        }
        val done=textureHead(tInfo,tHead,hashes.bytes(),rows.bytes())
        return chunk(0xB3300000.toInt(),cat(done[0],chunk(0xB3320000.toInt(),cat(done[1],chunk(0x33320002,data.bytes())))))
    }
    private fun textureHead(tInfo:ByteArray,tHead:ByteArray,hashes:ByteArray,table:ByteArray):Array<ByteArray>{
        val head=cat(chunk(0x33310001,tInfo),chunk(0x33310002,hashes),chunk(0x33310003,table))
        return arrayOf(cat(chunk(0,ByteArray(48)),chunk(0xB3310000.toInt(),head),chunk(0,ByteArray(28))),
            cat(chunk(0x33320001,tHead),chunk(0,ByteArray(80))))
    }
    private const val CLASS_ROW=0x5E970CBC
    private val COLL_ROW=0x8E112EB7.toInt()
    private val TYPE_NAME=Pattern.compile("[A-Za-z_]\\w*::[\\w:]+")
    private fun pair(a:Int,b:Int):Long=(a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
    private class VltClass{
        var layout=0
        val fields:MutableMap<Int,IntArray> = HashMap()
    }
    private class Place{
        var bin=-1;var inline=-1
        var def:IntArray?=null
    }
    private fun fourcc(s:String):Int=i32(latin(s),0)
    private class Vault(b:ByteArray){
        val head=Arrays.copyOf(b,0x80)
        val bin:Buf
        val vlt:ByteArray
        val order:MutableList<IntArray> = ArrayList()
        val datAt:Int;val datSize:Int;val dataEnd:Int
        val rows:MutableList<IntArray> = ArrayList()
        val newRows:MutableList<IntArray> = ArrayList()
        val ptrs:MutableList<IntArray> = ArrayList()
        val fixVlt:MutableMap<Int,IntArray> = HashMap()
        val binFix:MutableMap<Int,IntArray> = HashMap()
        val newFixVlt:MutableList<IntArray> = ArrayList()
        val newFixBin:MutableList<IntArray> = ArrayList()
        val newFixVltAt:MutableMap<Int,IntArray> = HashMap()
        val newVlt=Buf()
        var classes:MutableMap<Int,VltClass> = HashMap()
        var typeNames:MutableMap<Int,String> = HashMap()
        val aligns:MutableMap<Long,Int> = HashMap()
        val colls:MutableMap<Long,Int> = HashMap()
        init{
            val binsz=i32(b,0x14);val vltsz=i32(b,0x18)
            val binoff=i32(b,0x1C);val vltoff=i32(b,0x20)
            bin=Buf(sub(b,binoff,binoff+binsz));vlt=sub(b,vltoff,vltoff+vltsz)
            var off=0;var dat=-1;var datN=0;var exp=-1;var ptr=-1;var ptrN=0
            while(off+8<=vlt.size){
                val id=i32(vlt,off);val s=i32(vlt,off+4)
                if(s<8)break
                order.add(intArrayOf(id,off,s))
                if(id==fourcc("NtaD")){dat=off;datN=s}
                else if(id==fourcc("NpxE"))exp=off
                else if(id==fourcc("NrtP")){ptr=off;ptrN=s}
                off+=s
            }
            if(dat<0||exp<0||ptr<0)throw IllegalArgumentException("not a vault")
            datAt=dat;datSize=datN;dataEnd=dat+datN
            val n=i32(vlt,exp+8)
            for(i in 0 until n){
                val r=IntArray(5){j->i32(vlt,exp+12+i*20+j*4)}
                rows.add(r)
            }
            for(i in 0 until (ptrN-8)/12){
                val at=ptr+8+i*12
                ptrs.add(intArrayOf(i32(vlt,at),u16(vlt,at+4),u16(vlt,at+6),i32(vlt,at+8)))
            }
            var cur=-1
            val fixBin=HashMap<Int,IntArray>()
            for(p in ptrs){
                if(p[1]==2)cur=p[2]
                else if(p[1]==0)break
                else (if(cur==0)fixVlt else fixBin)[p[0]]=p
            }
            binFix.putAll(fixBin)
            val m=TYPE_NAME.matcher(latin(bin.a,0,bin.n))
            while(m.find())typeNames[attribKey(m.group())]=m.group()
            for(r in rows){
                if(r[1]==CLASS_ROW){
                    val k=i32(vlt,r[4]);val nf=i32(vlt,r[4]+8)
                    val c=VltClass();c.layout=i32(vlt,r[4]+16)
                    val f=fixVlt[r[4]+12]!![3]
                    for(i in 0 until nf){
                        val at=f+i*16
                        val key=i32(bin.a,at)
                        c.fields[key]=intArrayOf(i32(bin.a,at+4),u16(bin.a,at+8),u16(bin.a,at+10),u16(bin.a,at+12),bin.a[at+14].toInt() and 255)
                        aligns[pair(k,key)]=1 shl (bin.a[at+15].toInt() and 255)
                    }
                    classes[k]=c
                }else if(r[1]==COLL_ROW){
                    colls[pair(i32(vlt,r[4]+4),i32(vlt,r[4]))]=r[4]
                }
            }
        }
        fun has(cls:String,node:String):Boolean=colls.containsKey(pair(attribKey(cls),attribKey(node)))
        private fun allFix(off:Int):IntArray?=newFixVltAt[off] ?: fixVlt[off]
        private fun read(off:Int,n:Int):ByteArray=if(off>=dataEnd)Arrays.copyOfRange(newVlt.a,off-dataEnd,off-dataEnd+n)
            else Arrays.copyOfRange(vlt,off,off+n)
        private fun write(off:Int,b:ByteArray){
            if(off>=dataEnd)System.arraycopy(b,0,newVlt.a,off-dataEnd,b.size)
            else System.arraycopy(b,0,vlt,off,b.size)
        }
        private fun addFixVlt(f:IntArray){newFixVlt.add(f);newFixVltAt.putIfAbsent(f[0],f)}
        private fun copy(off:Int,n:Int):Int{
            val base=align(bin.n,16)
            bin.zeros(base-bin.n)
            val part=Arrays.copyOfRange(bin.a,off,off+n)
            bin.add(part)
            var q=off
            while(q<off+n){
                val f=binFix[q]
                if(f!=null){
                    val moved=intArrayOf(base+q-off,f[1],f[2],f[3])
                    newFixBin.add(moved);binFix[moved[0]]=moved
                }
                q+=4
            }
            return base
        }
        fun cloneNode(cls:String,src:String,dst:String,parent:String?){
            val c=attribKey(cls)
            val at=colls[pair(c,attribKey(src))]!!
            val h=read(at,32)
            val ne=i32(h,20);val nt=i32(h,24) and 0xFFFF
            val vc=classes[c]!!
            val size=32+nt*4+ne*12
            val rec=read(at,size)
            put32(rec,0,attribKey(dst))
            if(parent!=null)put32(rec,8,attribKey(parent))
            newVlt.zeros(align(newVlt.n,8)-newVlt.n)
            val newAt=dataEnd+newVlt.n
            val lf=allFix(at+0x1C)
            if(lf!=null){
                val nl=copy(lf[3],vc.layout)
                addFixVlt(intArrayOf(newAt+0x1C,lf[1],lf[2],nl))
            }
            val entsAt=32+nt*4
            for(e in 0 until ne){
                val fk=i32(rec,entsAt+e*12);val fl=u16(rec,entsAt+e*12+10)
                val pf=allFix(at+entsAt+e*12+4);val d=vc.fields[fk]
                if(pf==null||(fl and 0x20)!=0||d==null)continue
                val al=aligns[pair(c,fk)]?:4
                val nv=copy(pf[3],valueSize(pf[3],d,al))
                addFixVlt(intArrayOf(newAt+entsAt+e*12+4,pf[1],pf[2],nv))
            }
            newVlt.add(rec)
            newRows.add(intArrayOf(attribKey(String.format("%08x/%s",c,dst)),COLL_ROW,0,size,newAt))
            colls[pair(c,attribKey(dst))]=newAt
        }
        private fun valueSize(off:Int,d:IntArray,al:Int):Int{
            if((d[4] and 1)!=0){
                val cap=u16(bin.a,off+2);val el=u16(bin.a,off+4)
                return align(8,al)+cap*el
            }
            return d[2]
        }
        fun fieldPlace(cls:String,node:String,field:String,index:Int):Place{
            val c=attribKey(cls);val fk=attribKey(field)
            val d=classes[c]!!.fields[fk]
            val out=Place();out.def=d
            if(d==null)return out
            val at=colls[pair(c,attribKey(node))]!!
            val h=read(at+20,8)
            val ne=i32(h,0);val nt=i32(h,4) and 0xFFFF
            val ents=at+32+nt*4;val al=aligns[pair(c,fk)]?:4
            if((d[4] and 2)!=0){
                val f=allFix(at+0x1C) ?: return out
                if((d[4] and 1)==0){out.bin=f[3]+d[1];return out}
                out.bin=arrayItem(f[3]+d[1],index,al);return out
            }
            for(e in 0 until ne){
                val ent=read(ents+e*12,12)
                if(i32(ent,0)!=fk)continue
                if((u16(ent,10) and 0x20)!=0){out.inline=ents+e*12+4;return out}
                val f=allFix(ents+e*12+4) ?: return out
                out.bin=if((d[4] and 1)!=0)arrayItem(f[3],index,al)else f[3]
                return out
            }
            return out
        }
        private fun arrayItem(base:Int,index:Int,al:Int):Int{
            val count=u16(bin.a,base);val cap=u16(bin.a,base+2);val el=u16(bin.a,base+4)
            if(index>=cap)return -1
            if(index>=count)put16(bin.a,base,index+1)
            return base+align(8,al)+index*el
        }
        fun setValue(cls:String,node:String,field:String,index:Int,member:String?,text:String):String?{
            val p=fieldPlace(cls,node,field,index)
            val def=p.def ?: return "no such field"
            if(p.bin<0 && p.inline<0)return "not in this node"
            var at=0
            val raw:ByteArray?
            if(member!=null){
                val m=MEMBERS[member] ?: return "unknown member $member"
                at=m
                raw=if(member=="Collection"||member=="Class")ints(attribKey(text))else packFloat(text)
            }else{
                val type=typeNames[def[0]] ?: ""
                raw=packScalar(text,type,def[2])
            }
            if(raw==null)return "value $text"
            if(p.inline>=0)write(p.inline+at,raw)else System.arraycopy(raw,0,bin.a,p.bin+at,raw.size)
            return null
        }
        fun setModel(node:String,name:String){
            val place=fieldPlace("pvehicle",node,"MODEL",0).bin
            val k=stringKey64(name)
            put32(bin.a,place,k.toInt());put32(bin.a,place+4,(k ushr 32).toInt())
            put32(bin.a,place+8,attribKey(name));setText(place+12,name)
        }
        fun setLayoutText(cls:String,node:String,offset:Int,text:String){
            val place=colls[pair(attribKey(cls),attribKey(node))]!!
            setText(allFix(place+0x1C)!![3]+offset,text)
        }
        fun setMake(node:String,manufacturer:String):String{
            val place=colls[pair(attribKey("frontend"),attribKey(node))]!!
            val at=allFix(place+0x1C)!![3]+MAKE_OFFSET
            var make=makeIndex(manufacturer)
            if(make<=0)make=bin.a[at].toInt() and 255
            if(make<=0||make>=MAKES.size)make=makeIndex("LAMBORGHINI")
            bin.a[at]=make.toByte()
            return MAKES[make]
        }
        fun setWheels(node:String,w:WheelPlaces){
            for(i in 0..3){
                val at=fieldPlace("ecar",node,"TireOffsets",i).bin
                if(at<0)continue
                val isFront=i<2;val y=f32(bin.a,at+4)
                putF(bin.a,at,(if(isFront)w.frontX else w.rearX).toFloat())
                putF(bin.a,at+4,(if(y<0)-(if(isFront)w.frontY else w.rearY)else(if(isFront)w.frontY else w.rearY)).toFloat())
                putF(bin.a,at+12,w.radius.toFloat())
                val skid=fieldPlace("ecar",node,"TireSkidWidth",i).bin
                if(skid>=0)putF(bin.a,skid,w.width.toFloat())
            }
        }
        private fun setText(ptr:Int,text:String){
            val at=bin.n
            bin.add(latin(text+"\u0000"))
            newFixBin.removeAll{it[0]==ptr}
            val f=intArrayOf(ptr,3,1,at)
            newFixBin.add(f);binFix[ptr]=f
        }
        fun write():ByteArray{
            val v=Buf()
            for(ch in order){
                when(ch[0]){
                    fourcc("NtaD")->{
                        val bodyN=datSize-8+newVlt.n;val n=align(8+bodyN,16)
                        v.add(ints(ch[0],n)).add(vlt,datAt+8,datSize-8).add(newVlt.a,0,newVlt.n).zeros(n-8-bodyN)
                    }
                    fourcc("NpxE")->{
                        val body=Buf();body.add(ints(rows.size+newRows.size))
                        for(r in rows)body.add(ints(*r))
                        for(r in newRows)body.add(ints(*r))
                        val padFrom=ch[1]+12+rows.size*20;val padN=ch[1]+ch[2]-padFrom
                        val n=align(8+body.n+padN,16)
                        v.add(ints(ch[0],n)).add(body.a,0,body.n).add(vlt,padFrom,padN).zeros(n-8-body.n-padN)
                    }
                    fourcc("NrtP")->{
                        val out=Buf();var cur=-1
                        for(p in ptrs){
                            if(p[1]==2){if(cur>=0)putFixes(out,cur);cur=p[2]}
                            else if(p[1]==0){if(cur>=0)putFixes(out,cur);cur=-1}
                            putFix(out,p)
                        }
                        val n=align(8+out.n,16)
                        v.add(ints(ch[0],n)).add(out.a,0,out.n).zeros(n-8-out.n)
                    }
                    else->v.add(vlt,ch[1],ch[2])
                }
            }
            val vltOff=align(0x80+bin.n,0x80)
            val h=head.clone()
            put32(h,0x14,bin.n);put32(h,0x18,v.n);put32(h,0x1C,0x80);put32(h,0x20,vltOff)
            val out=Buf()
            out.add(h).add(bin.a,0,bin.n).zeros(vltOff-0x80-bin.n).add(v.a,0,v.n)
            out.zeros(align(out.n,0x80)-out.n)
            return out.bytes()
        }
        private fun putFixes(out:Buf,group:Int){for(f in if(group==0)newFixVlt else newFixBin)putFix(out,f)}
        private fun putFix(out:Buf,f:IntArray){
            val r=ByteArray(12)
            put32(r,0,f[0]);put16(r,4,f[1]);put16(r,6,f[2]);put32(r,8,f[3]);out.add(r)
        }
    }
    private val MEMBERS=hashMapOf(
        "Front" to 0,"Rear" to 4,"X" to 0,"Y" to 4,"Z" to 8,"W" to 12,"DegPerG" to 0,"DegPerSec" to 4,"MaxG" to 8,
        "Class" to 0,"Collection" to 4
    )
    private fun packFloat(text:String):ByteArray?=try{ints(text.toDouble().toFloat().toRawBits())}catch(_:NumberFormatException){null}
    private fun packScalar(text:String,type:String,size:Int):ByteArray?{
        return try{
            if(type.contains("Float"))return packFloat(text)
            if(type.contains("Bool")){
                val t=text.lowercase(Locale.ROOT)
                return byteArrayOf((if(t=="true"||t=="1")1 else 0).toByte())
            }
            val t=text.lowercase(Locale.ROOT)
            if(type.contains("Int")||type.contains("Enum")||t.startsWith("0x")){
                val neg=t.startsWith("-")
                val digits=if(neg)t.substring(1)else t
                var v=if(digits.startsWith("0x"))BigInteger(digits.substring(2),16)else BigInteger(digits)
                if(neg)v=v.negate()
                return ByteArray(size){i->v.shiftRight(i*8).toByte()}
            }
            null
        }catch(_:NumberFormatException){null}
    }
    private val FIELD=Pattern.compile("(\\w+)(?:\\[(\\d+)\\])?")
    private fun applyScript(main:Vault,fe:Vault,script:ByteArray,log:Consumer<String>){
        val skipped=TreeMap<String,Int>();var done=0
        for(line in latin(script,0,script.size).split("\n")){
            val t=line.trim();if(t.isEmpty())continue
            val w=t.split(Regex("\\s+"));val op=w[0];val cls=if(w.size>1)w[1]else ""
            if(cls=="emitterdata"||cls=="emittergroup")continue
            val v=if(cls=="frontend")fe else main
            if(op=="copy_node"&&w.size>=5){
                if(!v.has(cls,w[2])){
                    val k="copy $cls ${w[2]}: source missing";skipped[k]=(skipped[k]?:0)+1
                    continue
                }
                if(v.has(cls,w[4]))continue
                v.cloneNode(cls,w[2],w[4],w[3]);done++
            }else if(op=="update_field"&&w.size>=5){
                val node=w[2];val m=FIELD.matcher(w[3]);if(!m.matches())continue
                val name=requireNotNull(m.group(1));val index=m.group(2)?.toInt() ?: 0
                val member=if(w.size>5)w[4]else null;val text=w.last()
                if(!v.has(cls,node)){
                    val k="update of $cls $node";skipped[k]=(skipped[k]?:0)+1
                    continue
                }
                if(name=="MODEL"){v.setModel(node,text);continue}
                val why=v.setValue(cls,node,name,index,member,text)
                if(why!=null){val k="$cls.$name: $why";skipped[k]=(skipped[k]?:0)+1}else done++
            }else if((op=="add_field"||op=="resize_field")&&w.size>3){
                val k="$op $cls.${w[3]}";skipped[k]=(skipped[k]?:0)+1
            }
        }
        log.accept("vault: $done script lines applied")
        for((k,v) in skipped)log.accept("  skipped $k ($v)")
    }
    private fun carNames(g:ByteArray):MutableList<String>{
        for(c in chunks(g,0,g.size)){
            if(c[0]!=0x34600)continue
            val names=ArrayList<String>()
            for(i in 0 until 84)names.add(text(g,c[2]+8+i*0xD0,c[2]+8+i*0xD0+16))
            return names
        }
        throw Failure(Failure.Reason.UNSUPPORTED,"no car table in the game's files")
    }
    private fun insert(g:ByteArray,at:Int,what:ByteArray):ByteArray{
        val out=ByteArray(g.size+what.size)
        System.arraycopy(g,0,out,0,at)
        System.arraycopy(what,0,out,at,what.size)
        System.arraycopy(g,at,out,at+what.size,g.size-at)
        return out
    }
    private fun patchGlobal(gIn:ByteArray,cars:List<Kept>,log:Consumer<String>):ByteArray{
        var g=gIn
        val sub=HashMap<Int,IntArray>()
        var types:IntArray?=null;var partsChunk:IntArray?=null;var collisions:IntArray?=null
        for(c in chunks(g,0,g.size)){
            if(c[0]==0x34600)types=c
            else if(c[0]==0x80034602.toInt()){
                partsChunk=c
                for(c2 in chunks(g,c[2],c[2]+c[1]))sub[c2[0]]=c2
            }else if(c[0]==0x8003B900.toInt())collisions=c
        }
        val names=carNames(g);val gallardo=names.indexOf("GALLARDO")
        if(types==null||partsChunk==null||collisions==null||gallardo<0||!sub.containsKey(0x3460B)||!sub.containsKey(0x34604)||!sub.containsKey(0x34603))
            throw Failure(Failure.Reason.UNSUPPORTED,"not the game's car tables")
        val s=collisions[1];val o=collisions[2];var cid=0;var item:ByteArray?=null
        for(c2 in chunks(g,o,o+s)){
            val p=payload(g,c2[2],c2[1])
            if(i32(g,p)==attribKey("GALLARDO")){
                item=Arrays.copyOfRange(g,p,c2[2]+c2[1]);cid=c2[0]
            }
        }
        val collision=item ?: throw Failure(Failure.Reason.UNSUPPORTED,"no Gallardo collision to copy")
        val added=Buf();val end=o+s
        for(car in cars){
            val fillN=Math.floorMod(16-(end+added.n+8)%16,16)
            val body=cat(fill(fillN,0x11),collision)
            put32(body,fillN,attribKey(car.car))
            val grow=align(8+body.size,0x80)
            added.add(ints(cid,grow-8)).add(body).zeros(grow-8-body.size)
        }
        g=insert(g,end,added.bytes())
        put32(g,o-4,s+added.n)
        val tab=sub[0x3460B]!!;val parts=sub[0x34604]!!;val head=sub[0x34603]!!
        val carCount=tab[1]/4;val rows=parts[1]/14;var template=-1
        for(i in 0 until carCount)if(i32(g,tab[2]+i*4)==bhash(TEMPLATE_PARTS))template=i
        if(template<0)throw Failure(Failure.Reason.UNSUPPORTED,"no $TEMPLATE_PARTS in the parts' car table")
        if(carCount+cars.size>255)throw Failure(Failure.Reason.UNSUPPORTED,"too many cars for the parts' table")
        val run=Buf()
        for(i in 0 until rows)if((g[parts[2]+i*14+7].toInt() and 255)==template)run.add(g,parts[2]+i*14,14)
        val one=run.bytes()
        hideExtraParts(g,sub,one,log)
        val newRows=Buf();val newHashes=Buf()
        for(i in cars.indices){
            val mine=one.clone();var r=0
            while(r<mine.size){mine[r+7]=(carCount+i).toByte();r+=14}
            newRows.add(mine);newHashes.add(ints(bhash(cars[i].car)))
        }
        val grown=newRows.n+newHashes.n;val pad=align(grown,0x80)-grown
        newRows.zeros(pad)
        g=insert(g,parts[2]+rows*14,newRows.bytes())
        put32(g,parts[2]-4,parts[1]+newRows.n)
        g=insert(g,tab[2]+tab[1],newHashes.bytes())
        put32(g,tab[2]-4,tab[1]+newHashes.n)
        put32(g,partsChunk[2]-4,partsChunk[1]+newRows.n+newHashes.n)
        put32(g,head[2]+0x28,carCount+cars.size)
        put32(g,head[2]+0x38,rows+cars.size*(one.size/14))
        val rowsAt=types[2]+8
        val rec=Arrays.copyOfRange(g,rowsAt+gallardo*0xD0,rowsAt+(gallardo+1)*0xD0)
        val newTypes=Buf();val none=ByteArray(0xD0)
        put32(none,0x90,STOCK_TYPES);newTypes.add(none)
        for(i in cars.indices){
            val car=cars[i];val r=rec.clone()
            System.arraycopy(Arrays.copyOf(latin(car.car),16),0,r,0,16)
            System.arraycopy(Arrays.copyOf(latin(car.car),16),0,r,0x10,16)
            System.arraycopy(Arrays.copyOf(latin("CARS\\${car.car}\\GEOMETRY.BIN"),32),0,r,0x20,32)
            System.arraycopy(Arrays.copyOf(latin(car.manufacturer),16),0,r,0x40,16)
            put32(r,0x50,bhash(car.car));put32(r,0x90,FIRST_ADDON_TYPE+i)
            newTypes.add(r)
        }
        newTypes.zeros(align(newTypes.n,0x80)-newTypes.n)
        g=insert(g,types[2]+types[1],newTypes.bytes())
        put32(g,types[2]-4,types[1]+newTypes.n)
        return g
    }
    private val EXTRA_PART=Pattern.compile("_KIT0[1-9]|_STYLE\\d\\d")
    private fun hideExtraParts(g:ByteArray,sub:Map<Int,IntArray>,rows:ByteArray,log:Consumer<String>){
        val strings=sub[0x34606]!!;val uses=sub[0x3460A]!!
        var hidden=0;var at=0
        while(at+14<=rows.size){
            val um=u16(rows,at+0xC)
            if(um*24<uses[1]){
                val flag=g[uses[2]+um*24].toInt() and 255
                val base=u16(g,uses[2]+um*24+2)
                if(flag!=0&&base!=0xFFFF){
                    val name=text(g,strings[2]+base*4,strings[2]+strings[1])
                    if(EXTRA_PART.matcher(name).lookingAt()){rows[at+4]=0xFF.toByte();hidden++}
                }
            }
            at+=14
        }
        log.accept("parts: $hidden of ${rows.size/14} other kits' hidden")
    }
    private fun rebuildMemoryFile(mf:ByteArray,changed:Map<Int,ByteArray>):ByteArray{
        val n=i32(mf,12)
        val out=Buf();out.add(mf,0,16+n*20);out.zeros(align(out.n,16)-out.n)
        for(i in 0 until n){
            val row=16+i*20
            val h=i32(mf,row);val place=i32(mf,row+4);val size=i32(mf,row+12)
            val d=changed[h]
            if(d!=null){put32(out.a,row+8,d.size);put32(out.a,row+12,d.size)}
            put32(out.a,row+4,out.n)
            if(d!=null)out.add(d)else out.add(mf,place,size)
            out.zeros(align(out.n,16)-out.n)
        }
        return out.bytes()
    }
    private fun buildZdir(disc:Disc,files:Map<String,Any>,alias:Map<String,String>,dir:File,built:Built){
        var last=0
        for(r in disc.recs.values)last=maxOf(last,r[1])
        if(disc.archiveSize(last)!=0L)last++
        var absBase=0L
        for(r in disc.recs.values)if(r[4]!=0)absBase=maxOf(absBase,(r[3].toLong() and 0xFFFFFFFFL)+(r[4]+2047)/2048)
        val recs:MutableMap<Int,IntArray> = TreeMap {a,b->Integer.compareUnsigned(a,b)}
        recs.putAll(disc.recs)
        val archive="NFS/ZZDATA$last.BIN"
        var written=0L
        val outFile=File(dir,archive.replace('/','_'))
        BufferedOutputStream(FileOutputStream(outFile),1 shl 16).use{out->
            val chunkBuf=ByteArray(1 shl 16)
            for((name,value) in files){
                val h=bhash(name);var size:Long
                if(value is ByteArray){out.write(value);size=value.size.toLong()}
                else{
                    size=0L
                    FileInputStream(value as File).use{input->
                        while(true){
                            val r=input.read(chunkBuf)
                            if(r<=0)break
                            out.write(chunkBuf,0,r);size+=r.toLong()
                        }
                    }
                }
                val sector=(written/2048).toInt()
                recs[h]=intArrayOf(h,last,sector,(absBase+sector).toInt(),size.toInt(),-1)
                val padded=(size+2047)/2048*2048
                var p=size;while(p<padded){out.write(0);p++}
                written+=padded
            }
        }
        for((name,target) in alias){
            val r=disc.recs[bhash(target)]!!.clone();r[0]=bhash(name);recs[r[0]]=r
        }
        val z=Buf()
        for(r in recs.values)z.add(ints(*r))
        built.files["NFS/ZDIR.BIN"]=z.bytes()
        built.files[archive]=null
    }
    private fun findFile(mod:Map<String,ByteArray>,nameRegex:String):String?{
        val p=Pattern.compile("(.*/)?$nameRegex",Pattern.CASE_INSENSITIVE)
        return mod.keys.sorted().firstOrNull{p.matcher(it).matches()}
    }
    @JvmStatic
    fun titleCase(car:String):String=if(car.isEmpty())car else car[0]+car.substring(1).lowercase(Locale.ROOT)
    private val GEO_NAME=Pattern.compile("(.*/)?cars?(?:_replace)?/([^/]+)/geometry\\.bin",Pattern.CASE_INSENSITIVE)
    private val LOOSE_GEO=Pattern.compile("(.*/)?([^/]+)/geometry\\.bin",Pattern.CASE_INSENSITIVE)
    private val BINARY_ROOT=Pattern.compile("(.*/)binary/.*",Pattern.CASE_INSENSITIVE)
    @JvmStatic
    fun carPaths(paths:Collection<String>):MutableList<String>{
        val geos=ArrayList<String>()
        for(k in paths)if(GEO_NAME.matcher(k).matches())geos.add(k)
        if(geos.isEmpty()){
            val lower=HashSet<String>();for(k in paths)lower.add(k.lowercase(Locale.ROOT))
            for(k in paths){
                if(LOOSE_GEO.matcher(k).matches()&&lower.contains(k.substring(0,k.length-"GEOMETRY.BIN".length).lowercase(Locale.ROOT)+"textures.bin"))geos.add(k)
            }
        }
        geos.sort()
        return geos
    }
    private fun geoMatch(geoPath:String):Matcher?{
        var m=GEO_NAME.matcher(geoPath)
        if(m.matches())return m
        m=LOOSE_GEO.matcher(geoPath)
        return if(m.matches())m else null
    }
    @JvmStatic
    fun carName(geoPath:String):String=geoMatch(geoPath)?.group(2)?.uppercase(Locale.ROOT) ?: geoPath
    @JvmStatic
    @Throws(IOException::class)
    fun carFiles(files:Map<String,File>,geoPath:String):MutableMap<String,ByteArray>{
        val binary=BINARY_ROOT.matcher(geoPath)
        val geo=geoMatch(geoPath)
        val root=if(binary.matches())binary.group(1)else geo?.group(1) ?: ""
        val folders=ArrayList<String>()
        for(other in carPaths(files.keys))if(other!=geoPath)folders.add(other.substring(0,other.length-"GEOMETRY.BIN".length))
        val out=HashMap<String,ByteArray>()
        for((k,value) in files){
            if(!k.startsWith(root)||folders.any{k.startsWith(it)})continue
            out[k]=Files.readAllBytes(value.toPath())
        }
        return out
    }
    private fun findScript(mod:Map<String,ByteArray>,car:String):String?{
        val p=Pattern.compile("(?:.*/)?([^/]*\\.nfsms)",Pattern.CASE_INSENSITIVE)
        val scripts=ArrayList<String>()
        for(k in mod.keys){
            val m=p.matcher(k)
            if(m.matches()&&!requireNotNull(m.group(1)).lowercase(Locale.ROOT).contains("uninstall"))scripts.add(k)
        }
        scripts.sort()
        val named=car.lowercase(Locale.ROOT)
        for(k in scripts)if(k.substring(k.lastIndexOf('/')+1).lowercase(Locale.ROOT).contains(named))return k
        for(k in scripts)if(k.substring(k.lastIndexOf('/')+1).lowercase(Locale.ROOT).contains("install"))return k
        return if(scripts.size==1)scripts[0]else null
    }
    @JvmStatic
    @Throws(Failure::class, IOException::class)
    fun convert(disc:Disc,mod:Map<String,ByteArray>,geoPath:String,maxTexturesKb:Int,log:Consumer<String>):Package{
        val car=carName(geoPath)
        if(car.length>15||("CARS\\$car\\GEOMETRY.BIN").length>31)throw Failure(Failure.Reason.NAME_TOO_LONG,car)
        if(car==TEMPLATE_PARTS)throw Failure(Failure.Reason.UNSUPPORTED,"a car named $car")
        val pack=Package();pack.car=car
        val mg=mod[geoPath] ?: throw Failure(Failure.Reason.NO_CAR,"no GEOMETRY.BIN at $geoPath")
        var mt:ByteArray?=null
        val want=geoPath.substring(0,geoPath.length-"GEOMETRY.BIN".length)+"TEXTURES.BIN"
        for(k in mod.keys)if(k.equals(want,ignoreCase=true))mt=mod[k]
        val textureBytes=mt ?: throw Failure(Failure.Reason.NO_CAR,"no TEXTURES.BIN beside $geoPath")
        val solids=ArrayList<Solid>()
        for(d in readModSolids(mg)){
            val s=convertSolid(d,car)
            if(s!=null)solids.add(s)
        }
        if(solids.isEmpty())throw Failure(Failure.Reason.UNSUPPORTED,"no solid the converter knows in GEOMETRY.BIN")
        log.accept("geometry: ${solids.size} solids")
        reduceLods(solids,log);mergeAttachments(solids,log)
        pack.geometry=buildGeometry(solids,disc.load("CARS\\GALLARDO\\GEOMETRY.BIN"))
        val tex=readModTextures(textureBytes)
        shrinkTextures(tex,maxTexturesKb*1024,log)
        pack.textures=buildTextures(tex,disc.load("CARS\\GALLARDO\\TEXTURES.BIN"),car)
        pack.manufacturer="LAMBORGHINI"
        val ci=findFile(mod,"cartypeinfo[^/]*\\.bin")
        if(ci!=null){
            val raw=mod[ci]!!
            val m=Pattern.compile(Pattern.quote(car)+"\u0000([A-Z0-9 ]+)\u0000").matcher(latin(raw,0,raw.size))
            if(m.find())pack.manufacturer=requireNotNull(m.group(1))
        }
        val script=findScript(mod,car)
        if(script!=null)pack.script=mod[script]
        pack.title=titleCase(car)
        val ends=ArrayList<String>()
        for(k in mod.keys)if(k.lowercase(Locale.ROOT).endsWith(".end"))ends.add(k)
        ends.sortWith(compareBy<String>({!it.lowercase(Locale.ROOT).endsWith("english.end")},{it}))
        val named=Pattern.compile("CARNAME_([A-Z0-9]+)_"+Pattern.quote(car)+"\\s+\"([^\"]+)\"")
        var found:Array<String>?=null;var any:Array<String>?=null
        for(k in ends){
            val raw=mod[k]!!;val m=named.matcher(latin(raw,0,raw.size))
            while(found==null&&m.find()){
                if(m.group(1)==pack.manufacturer)found=arrayOf(m.group(1),m.group(2))
                else if(any==null)any=arrayOf(m.group(1),m.group(2))
            }
            if(found!=null)break
        }
        if(found==null&&ci==null)found=any
        if(found!=null){pack.manufacturer=found[0];pack.title=found[1]}
        val logo=findFile(mod,Pattern.quote("SECONDARY_LOGO_$car.dds"))
        if(logo!=null){
            val dds=mod[logo]!!
            if(dds.size>=128){
                val fmt=latin(dds,84,4)
                val lh=i32(dds,12);val lw=i32(dds,16)
                if(xboxFormat(fmt)>=0){
                    pack.logo=sub(dds,128,128+((lw+3)/4)*((lh+3)/4)*blockSize(fmt))
                    pack.logoWidth=lw;pack.logoHeight=lh;pack.logoFormat=xboxFormat(fmt)
                }
            }
        }
        return pack
    }
    @JvmStatic
    fun isStockCar(disc:Disc,car:String):Boolean=carNames(jdlzDecode(disc.load("GLOBAL\\GLOBALB.LZC"))).contains(car)
    @JvmStatic
    fun setLogo(pack:Package,argb:IntArray,width:Int,height:Int){
        val out=ByteArray(width/4*(height/4)*16)
        var at=0;var y=0
        while(y+4<=height){
            var x=0
            while(x+4<=width){
                for(r in 0..3){
                    var row=0
                    for(c in 0..3)row=row or ((argb[(y+r)*width+x+c] ushr 28) shl (c*4))
                    put16(out,at+r*2,row)
                }
                put16(out,at+8,0xFFFF);put16(out,at+10,0xFFFF)
                x+=4;at+=16
            }
            y+=4
        }
        pack.logo=out;pack.logoWidth=width;pack.logoHeight=height;pack.logoFormat=xboxFormat("DXT3")
    }
    @JvmStatic
    fun logoFile(car:String):String=String.format("name_%08x.dxt",bhash("SECONDARY_LOGO_$car"))
    @JvmStatic
    @Throws(Failure::class, IOException::class)
    fun build(disc:Disc,kept:List<Kept>,dir:File,log:Consumer<String>):Built{
        val built=Built()
        var g=jdlzDecode(disc.load("GLOBAL\\GLOBALB.LZC"))
        val stock=carNames(g);val addons=ArrayList<Kept>()
        var files:MutableMap<String,Any> = LinkedHashMap()
        val alias=LinkedHashMap<String,String>()
        for(k in kept){
            if(stock.contains(k.car))built.replaced.add(k.car)
            else{addons.add(k);built.addons.add(k.car)}
            files["CARS\\${k.car}\\GEOMETRY.BIN"]=k.geometry
            files["CARS\\${k.car}\\TEXTURES.BIN"]=k.textures
        }
        if(addons.size>MAX_ADDONS)throw Failure(Failure.Reason.UNSUPPORTED,"more than $MAX_ADDONS add-on cars")
        if(addons.isNotEmpty()){
            g=patchGlobal(g,addons,log)
            val lzc=jdlzEncode(g)
            if(!jdlzDecode(lzc).contentEquals(g))throw IllegalStateException("JDLZ encoder failed its round trip")
            val v=Vault(disc.load("GLOBAL\\ATTRIBUTES.BIN"))
            val fe=Vault(disc.load("GLOBAL\\FE_ATTRIB.BIN"))
            fe.classes=v.classes;fe.typeNames=v.typeNames
            val list=StringBuilder()
            for(k in addons){
                val node=k.car.lowercase(Locale.ROOT)
                val script=k.script
                if(script!=null)applyScript(v,fe,script,log)
                if(!v.has("ecar",node)){
                    v.cloneNode("ecar","gallardo",node,null)
                    val w=measureWheels(readBuiltSolids(Files.readAllBytes(k.geometry.toPath())))
                    if(w!=null){
                        v.setWheels(node,w)
                        log.accept(String.format(Locale.ROOT,"%s: wheels at x %.3f, %.3f, y %.3f, %.3f, radius %.3f",k.car,w.frontX,w.rearX,w.frontY,w.rearY,w.radius))
                    }
                }
                if(!v.has("pvehicle",node))v.cloneNode("pvehicle","gallardo",node,null)
                if(!fe.has("frontend",node))fe.cloneNode("frontend","gallardo",node,null)
                v.setValue("pvehicle",node,"frontend",0,"Collection",node)
                v.setModel(node,k.car)
                fe.setLayoutText("frontend",node,0x18,k.car)
                val make=fe.setMake(node,k.manufacturer)
                alias["CARS\\${k.car}\\VINYLS.BIN"]="CARS\\GALLARDO\\VINYLS.BIN"
                alias["CARS\\${k.car}\\PREVINYL.BIN"]="CARS\\GALLARDO\\PREVINYL.BIN"
                var logoInfo="0 0 0 0"
                if(k.logo!=null){
                    built.files[logoFile(k.car)]=k.logo
                    logoInfo=String.format("%08x %d %d %d",bhash("SECONDARY_LOGO_${k.car}"),k.logoWidth,k.logoHeight,k.logoFormat)
                }
                list.append(String.format("%08x %08x %08x %s %s\n",attribKey(node),attribKey(node),bhash("CARNAME_${make}_${k.car}"),logoInfo,k.title))
            }
            val attributes=v.write()
            val memoryFiles=HashMap<Int,ByteArray>()
            memoryFiles[bhash("GLOBAL\\ATTRIBUTES.BIN")]=attributes
            memoryFiles[bhash("GLOBAL\\GLOBALB.LZC")]=lzc
            val memory=rebuildMemoryFile(disc.load("GLOBAL\\GLOBALMEMORYFILE.BIN"),memoryFiles)
            val first=LinkedHashMap<String,Any>()
            first["GLOBAL\\GLOBALB.LZC"]=lzc
            first["GLOBAL\\GLOBALB.BUN"]=g
            first["GLOBAL\\ATTRIBUTES.BIN"]=attributes
            first["GLOBAL\\GLOBALMEMORYFILE.BIN"]=memory
            first["GLOBAL\\FE_ATTRIB.BIN"]=fe.write()
            first.putAll(files);files=first
            built.files["cars.txt"]=latin(list.toString())
            built.files["car_types.txt"]=latin("${FIRST_ADDON_TYPE+addons.size}\n")
        }
        buildZdir(disc,files,alias,dir,built)
        return built
    }
}
