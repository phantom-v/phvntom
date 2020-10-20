package mem

import chisel3._
import chisel3.util._
import rv64_3stage._
import rv64_3stage.ControlConst._
import bus._
import device._
import utils._

class L2CacheXbar(val n_sources: Int = 1)(implicit val cacheConfig: CacheConfig)
    extends Module
    with CacheParameters {
  val io = IO(new Bundle {
    val in = Vec(n_sources, new MemIO(blockBits))
    val out = Flipped(new MemIO(lineBits))
  })

  val s_idle :: s_readResp :: s_writeResp :: Nil = Enum(3)
  val state = RegInit(s_idle)

  val inputArb = Module(new Arbiter(new MemReq(lineBits), n_sources))
  (inputArb.io.in zip io.in.map(_.req)).map { case (arb, in) => arb <> in }
  val thisReq = inputArb.io.out
  val inflightSrc = Reg(UInt(log2Up(n_sources).W))

  io.out.req.bits := thisReq.bits
  // bind correct valid and ready signals
  io.out.stall := false.B
  io.out.req.valid := thisReq.valid && (state === s_idle)
  thisReq.ready := io.out.req.ready && (state === s_idle)

  io.in.map(_.resp.bits := io.out.resp.bits)
  io.in.map(_.resp.valid := false.B)
  (io.in(inflightSrc).resp, io.out.resp) match {
    case (l, r) => {
      l.valid := r.valid
      r.ready := l.ready
    }
  }

  switch(state) {
    is(s_idle) {
      when(thisReq.fire()) {
        inflightSrc := inputArb.io.chosen
        when(thisReq.valid) {
          when(thisReq.bits.wen) { state := s_writeResp }.otherwise {
            state := s_readResp
          }
        }
      }
    }
    is(s_readResp) { when(io.out.resp.fire()) { state := s_idle } }
    is(s_writeResp) { when(io.out.resp.fire()) { state := s_idle } }
  }

  // printf(p"[${GTimer()}]: L2CacheXbar Debug Start-----------\n")
  // printf(p"state=${state},inflightSrc=${inflightSrc}\n")
  // for (i <- 0 until n_sources) {
  //   printf(p"----------l2cache io.in(${i})----------\n")
  //   printf(p"${io.in(i)}\n")
  // }
  // printf(p"----------l2cache io.out----------\n")
  // printf(p"${io.out}\n")
  // printf("--------------------------------\n")
}

class L2Cache(val n_sources: Int = 1)(implicit val cacheConfig: CacheConfig)
    extends Module
    with CacheParameters {
  val io = IO(new L2CacheIO(n_sources))

  // Module Used
  val metaArray = Mem(nSets, Vec(nWays, new MetaData))
  val dataArray = Mem(nSets, Vec(nWays, new CacheLineData))
  val arbiter = Module(new L2CacheXbar(n_sources))
  for (i <- 0 until n_sources) {
    io.in(i) <> arbiter.io.in(i)
  }
  val current_request = arbiter.io.out

  val s1_valid = RegInit(Bool(), false.B)
  val s1_addr = Reg(UInt(xlen.W))
  val s1_index = Wire(UInt(indexLength.W))
  val s1_data = Reg(UInt(blockBits.W))
  val s1_wen = RegInit(Bool(), false.B)
  val s1_memtype = Reg(UInt(xlen.W))
  val s1_meta = Wire(Vec(nWays, new MetaData))
  val s1_cacheline = Wire(Vec(nWays, new CacheLineData))
  val s1_tag = Wire(UInt(tagLength.W))
  val s1_lineoffset = Wire(UInt(lineLength.W))
  val s1_wordoffset = Wire(UInt((offsetLength - lineLength).W))

  when(current_request.req.fire()) {
    s1_valid := current_request.req.valid
    s1_addr := current_request.req.bits.addr
    s1_data := current_request.req.bits.data
    s1_wen := current_request.req.bits.wen
    s1_memtype := current_request.req.bits.memtype
  }

  s1_index := s1_addr(indexLength + offsetLength - 1, offsetLength)
  s1_meta := metaArray(s1_index)
  s1_cacheline := dataArray(s1_index)
  s1_tag := s1_addr(xlen - 1, xlen - tagLength)
  s1_lineoffset := s1_addr(offsetLength - 1, offsetLength - lineLength)
  s1_wordoffset := s1_addr(offsetLength - lineLength - 1, 0)

  val hit_vec = VecInit(s1_meta.map(m => m.valid && m.tag === s1_tag)).asUInt
  val hit_index = PriorityEncoder(hit_vec)
  val victim_index = policy.choose_victim(s1_meta)
  val victim_vec = UIntToOH(victim_index)
  val hit = hit_vec.orR
  val result = Wire(UInt(blockBits.W))
  val access_index = Mux(hit, hit_index, victim_index)
  val access_vec = UIntToOH(access_index)
  val cacheline_meta = s1_meta(access_index)
  val cacheline_data = s1_cacheline(access_index)

  val s_idle :: s_memReadReq :: s_memReadResp :: s_memWriteReq :: s_memWriteResp :: Nil =
    Enum(5)
  val state = RegInit(s_idle)
  val read_address = Cat(s1_addr(xlen - 1, offsetLength), 0.U(offsetLength.W))
  val write_address = Cat(cacheline_meta.tag, s1_index, 0.U(offsetLength.W))
  val mem_valid = state === s_memReadResp && io.mem.resp.valid
  val request_satisfied = hit || mem_valid

  current_request.resp.valid := s1_valid && request_satisfied
  current_request.resp.bits.data := result
  current_request.req.ready := state === s_idle

  io.mem.stall := false.B
  io.mem.req.valid := s1_valid && (state === s_memReadReq || state === s_memReadResp || state === s_memWriteReq || state === s_memWriteResp)
  io.mem.req.bits.addr := Mux(
    state === s_memWriteReq || state === s_memWriteResp,
    write_address,
    read_address
  )
  io.mem.req.bits.data := cacheline_data.asUInt
  io.mem.req.bits.wen := state === s_memWriteReq || state === s_memWriteResp
  io.mem.req.bits.memtype := DontCare
  io.mem.resp.ready := s1_valid && (state === s_memReadResp || state === s_memWriteResp)

  switch(state) {
    is(s_idle) {
      when(!hit && s1_valid) {
        state := Mux(cacheline_meta.dirty, s_memWriteReq, s_memReadReq)
      }
    }
    is(s_memReadReq) { when(io.mem.req.fire()) { state := s_memReadResp } }
    is(s_memReadResp) { when(io.mem.resp.fire()) { state := s_idle } }
    is(s_memWriteReq) { when(io.mem.req.fire()) { state := s_memWriteResp } }
    is(s_memWriteResp) { when(io.mem.resp.fire()) { state := s_memReadReq } }
  }

  val fetched_data = io.mem.resp.bits.data
  val fetched_vec = Wire(new CacheLineData)
  for (i <- 0 until nLine) {
    fetched_vec.data(i) := fetched_data((i + 1) * blockBits - 1, i * blockBits)
  }

  val target_data = Mux(hit, cacheline_data, fetched_vec)
  result := DontCare
  when(s1_valid) {
    when(request_satisfied) {
      when(s1_wen) {
        val newdata = Wire(new CacheLineData)
        newdata := target_data
        newdata.data(s1_lineoffset) := s1_data
        val write_data = VecInit(Seq.fill(nWays)(newdata))
        dataArray.write(s1_index, write_data, access_vec.asBools)
        val new_meta = Wire(Vec(nWays, new MetaData))
        new_meta := policy.update_meta(s1_meta, access_index)
        new_meta(access_index).valid := true.B
        new_meta(access_index).dirty := true.B
        new_meta(access_index).tag := s1_tag
        metaArray.write(s1_index, new_meta)
        // printf(
        //   p"dcache write: s1_index=${s1_index}, access_index=${access_index}\n"
        // )
        // printf(p"\tnewdata=${newdata}\n")
        // printf(p"\tnew_meta=${new_meta}\n")
      }.otherwise {
        val result_data = target_data.data(s1_lineoffset)
        result := result_data
        val write_data = VecInit(Seq.fill(nWays)(target_data))
        dataArray.write(s1_index, write_data, access_vec.asBools)
        val new_meta = Wire(Vec(nWays, new MetaData))
        new_meta := policy.update_meta(s1_meta, access_index)
        new_meta(access_index).valid := true.B
        when(!hit) {
          new_meta(access_index).dirty := false.B
        }
        new_meta(access_index).tag := s1_tag
        metaArray.write(s1_index, new_meta)
        // printf(
        //   p"dcache read update: s1_index=${s1_index}, access_index=${access_index}\n"
        // )
        // printf(p"\ttarget_data=${target_data}\n")
        // printf(p"\tnew_meta=${new_meta}\n")
      }
    }
  }

  // printf(p"[${GTimer()}]: ${cacheName} Debug Info----------\n")
  // printf(
  //   "state=%d, hit=%d, result=%x\n",
  //   state,
  //   hit,
  //   result
  // )
  // printf(
  //   "s1_valid=%d, s1_addr=%x, s1_index=%x\n",
  //   s1_valid,
  //   s1_addr,
  //   s1_index
  // )
  // printf(
  //   "s1_data=%x, s1_wen=%d, s1_memtype=%d\n",
  //   s1_data,
  //   s1_wen,
  //   s1_memtype
  // )
  // printf(
  //   "s1_tag=%x, s1_lineoffset=%x, s1_wordoffset=%x\n",
  //   s1_tag,
  //   s1_lineoffset,
  //   s1_wordoffset
  // )
  // printf(p"hit_vec=${hit_vec}, access_index=${access_index}\n")
  // printf(
  //   p"victim_index=${victim_index}, victim_vec=${victim_vec}, access_vec = ${access_vec}\n"
  // )
  // printf(p"s1_cacheline=${s1_cacheline}\n")
  // printf(p"s1_meta=${s1_meta}\n")
  // // printf(p"cacheline_data=${cacheline_data}\n")
  // // printf(p"cacheline_meta=${cacheline_meta}\n")
  // // printf(p"dataArray(s1_index)=${dataArray(s1_index)}\n")
  // // printf(p"metaArray(s1_index)=${metaArray(s1_index)}\n")
  // // printf(p"fetched_data=${Hexadecimal(fetched_data)}\n")
  // // printf(p"fetched_vec=${fetched_vec}\n")
  // printf(p"----------${cacheName} io.mem----------\n")
  // printf(p"${io.mem}\n")
  // printf("-----------------------------------------------\n")
}