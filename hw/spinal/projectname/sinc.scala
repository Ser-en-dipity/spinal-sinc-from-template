package projectname

import spinal.core._
import spinal.lib._
import spinal.core.sim._
import spinal.sim._

// Hardware definition
case class MyIntegrator(IW: Int, OW: Int, DR: Int, Order: Int) extends Component {
  val io = new Bundle {
    val i_data = in port SInt(IW bits)
    val i_valid = in port Bool()
    val o_data  = out port SInt(OW bits)
    val o_valid = out port Bool()
  }

  // 创建累加器寄存器（使用默认时钟域）
  val accum = Reg(SInt(OW bits)) init(0)
  accum.simPublic()  // 使信号在仿真中可访问
  
  // 默认输出值
  io.o_valid := io.i_valid
  val sum = accum + io.i_data.resize(OW)
  io.o_data := sum
  
  // 时钟域逻辑（在默认时钟的上升沿执行）
  when(io.i_valid) {
    accum := sum
  } 
}
case class MyDecimator(W: Int, DR: Int) extends Component {
  val io = new Bundle {
    val i_data = in port SInt(W bits)
    val i_valid = in port Bool()
    val o_data  = out port SInt(W bits)
    val o_valid = out port Bool()
  }

  val data_reg = Reg(SInt(W bits)) init(0)
  val counter = Reg(UInt(32 bits)) init(0)
  
  io.o_valid := False
  io.o_data := data_reg  // ✅ 输出寄存器值，保持稳定
  
  when(io.i_valid) {
    when(counter === (DR - 1)) {
      data_reg := io.i_data  // 保存数据到寄存器
      io.o_valid := True
      counter := 0
    } otherwise {
      counter := counter + 1
    }
  }
}

case class MyComb(IW: Int, OW: Int, N: Int) extends Component {
  val io = new Bundle {
    val i_data = in port SInt(IW bits)
    val i_valid = in port Bool()
    val o_data  = out port SInt(OW bits)
    val o_valid = out port Bool()
  }

  io.o_valid := io.i_valid
  val delay = Reg(SInt(OW bits)) init(0)
  val output_reg = Reg(SInt(OW bits)) init(0)
  
  io.o_data := output_reg  // 输出寄存器值，而不是组合逻辑
  
  when(io.i_valid) {
    output_reg := io.i_data.resize(OW) - delay  // 计算差分并保存
    delay := io.i_data.resize(OW)  // 更新延迟寄存器
  }
}

case class MySinc(IW: Int, OW: Int, DR: Int, Order: Int) extends Component {
  val io = new Bundle {
    val i_data = in port SInt(IW bits)
    val i_valid = in port Bool()
    val o_data  = out port SInt(OW bits)
    val o_valid = out port Bool()
    val debug_sine = in port SInt(OW bits)  // 调试信号：输入的正弦波值
  }

  val integrator_data = Array.fill(Order+1)(SInt(OW bits))
  val integrator_valid = Array.fill(Order+1)(Bool())

  // 连接第一个积分器到输入
  integrator_data(0) := io.i_data.resize(OW)
  integrator_valid(0) := io.i_valid

  // 串联连接所有积分器
  for(i <- 1 to Order) {
    val integrator = MyIntegrator(IW, OW, DR, Order)
    integrator.io.i_data := integrator_data(i-1)
    integrator.io.i_valid := integrator_valid(i-1)
    integrator_data(i) := integrator.io.o_data
    integrator_valid(i) := integrator.io.o_valid
  }
  
  val decimator = MyDecimator(OW, DR)
  decimator.io.i_data := integrator_data(Order)
  decimator.io.i_valid := integrator_valid(Order)

  val comb_data = Array.fill(Order+1)(SInt(OW bits))
  val comb_valid = Array.fill(Order+1)(Bool())

  comb_data(0) := decimator.io.o_data
  comb_valid(0) := decimator.io.o_valid

  for (i <- 1 to Order) {
    val comb = MyComb(OW, OW, 1)
    comb.io.i_data := comb_data(i-1)
    comb.io.i_valid := comb_valid(i-1)
    comb_data(i) := comb.io.o_data
    comb_valid(i) := comb.io.o_valid
  }

    // 连接最后一个积分器到输出
  io.o_data := comb_data(Order)
  io.o_valid := comb_valid(Order)

}

object MySincVerilog extends App {
  Config.spinal.generateVerilog(MySinc(22,22,64,3))
}

object TestWave extends App {
  // 测试 PWM 正弦波输入
  SimConfig.withWave.compile(new MySinc(22, 22, 8, 3)).doSim { dut =>
    import scala.math._
    import scala.util.Random
    
    // 启动时钟和复位
    dut.clockDomain.forkStimulus(period = 10)
    
    // 初始化输入
    dut.io.i_data #= 0
    dut.io.i_valid #= true
    dut.io.debug_sine #= 0  // 初始化调试信号
    
    // 等待复位完成
    dut.clockDomain.waitRisingEdge()
    
    println("=" * 60)
    println("测试 PWM 正弦波")
    println("信号: 50*sin(400*π*t), PWM频率100kHz")
    println("SINC滤波器: DR=8, Order=3, Fs_out=250kHz")
    println("=" * 60)
    
    val random = new Random()
    val samplingFreq = 2000000.0  // 2 MHz 采样频率
    val signalFreq = 200.0        // 200 Hz 信号频率
    val amplitude = 50.0
    val pwmFreq = 100000.0        // 100 kHz PWM 频率（每周期20个采样点）
    val totalSamples = 200000     // 总采样点数
    val noiseLevel = 0.05         // 5% 噪声
    val DR = 8                    // 抽取率 (与硬件参数一致)
    
    // 模拟 PWM 输入
    for (sample <- 0 until totalSamples) {
      val t = sample.toDouble / samplingFreq  // 修复：转换为 Double
      
      // 生成正弦波值 + 噪声
      val sineValue = sin(2 * Pi * signalFreq * t)
      val noise = random.nextGaussian() * noiseLevel * amplitude
    //   val signalWithNoise = sineValue + noise
      val signalWithNoise = sineValue 

      // 将模拟值转换为 PWM (归一化到 0-1)
      var duty = amplitude * (sineValue + 1)
      if (duty < 0) duty = 0
      else if (duty > 2 * amplitude) duty = 2 * amplitude

      val car_phase = pwmFreq * t
      val pwmThreshold = car_phase - floor(car_phase)
      val pwm = 2 * amplitude * pwmThreshold
      
      // PWM 比较: 如果归一化值大于阈值则输出1，否则0
      val pwmBit = if (duty > pwm) 1 else 0
      
      // 将正弦波值转换为整数（用于调试显示）
      val sineInt = (amplitude * sineValue).toInt
      
      // 输入到设计
      dut.io.i_data #= pwmBit
      dut.io.i_valid #= true
      dut.io.debug_sine #= sineInt  // 输入正弦波调试信号
      
      // 等待时钟
      dut.clockDomain.waitRisingEdge()
      
      // 读取输出
      val output = dut.io.o_data.toInt
      val valid = dut.io.o_valid.toBoolean
      
      // 只在valid=true时打印（有效的抽取输出）
      if (valid) {
        val gain = 512.0  // DR^Order = 8^3 = 512
        val scaledOutput = output / gain
        println(f"样本 $sample%6d: 时间=${t*1000}%.3fms, 正弦=$sineInt%4d, 输出=$output%8d, 缩放=$scaledOutput%6.2f")
      }
      
      // 进度指示
      if (sample % 10000 == 0 && sample > 0) {
        println(s"  [进度] 已处理 $sample 个样本...")
      }
    }
    
    println("=" * 60)
    println("仿真完成! VCD 波形文件已生成")
    println("=" * 60)
  }
}

object IntegratorTest extends App {
  SimConfig.withWave.compile(new MySinc(22, 22, 8, 3)).doSim { dut =>
    import scala.util.Random
    
    // 启动时钟和复位
    dut.clockDomain.forkStimulus(period = 10)
    
    // 初始化输入
    dut.io.i_data #= 0
    dut.io.i_valid #= true
    dut.io.debug_sine #= 0
    
    // 等待复位完成
    dut.clockDomain.waitRisingEdge()
    
    println("=" * 60)
    println("测试恒定输入（模拟50% duty cycle PWM）")
    println("=" * 60)
    
    // 输入恒定的1（模拟50% PWM），持续100个周期
    for (cycle <- 0 until 100) {
      // 交替输入0和1（50% duty cycle）
      val input = if (cycle % 2 == 0) 1 else 0
      
      dut.io.i_data #= input
      dut.io.i_valid #= true
      dut.io.debug_sine #= 0
      
      // 等待时钟
      dut.clockDomain.waitRisingEdge()
      
      val output = dut.io.o_data.toInt
      val valid = dut.io.o_valid.toBoolean
      
      if (valid || cycle < 20) {
        println(f"周期 $cycle%3d: 输入=$input, 输出=$output%6d, valid=$valid")
      }
    }
    
    println("=" * 60)
    println("仿真完成! VCD 波形文件已生成")
    println("=" * 60)
  }
}