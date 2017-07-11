package efficiency.pick_cellstate_resources

import ClusterSchedulingSimulation._

import scala.collection.mutable.{IndexedSeq, ListBuffer}
import scala.util.control.Breaks

/**
 * Created by dfernandez on 11/1/16.
 */
// This picker doesn't take into account yet shutted down machines nor capacity security margins nor performance
// TODO: Make a Quicksort-like strategy or pass a candidate index to iterate (e.g. the last successful candidate)
object GASimplePickerCandidatePower extends CellStateResourcesPicker{

  override def schedule(cellState: CellState, job: Job, scheduler: Scheduler, simulator: ClusterSimulator): ListBuffer[ClaimDelta] = {
    val claimDeltas = collection.mutable.ListBuffer[ClaimDelta]()
    var candidatePool = scheduler.candidatePoolCache.getOrElseUpdate(cellState.numberOfMachinesOn, Array.range(0, cellState.numMachines))
    var numRemainingTasks = job.unscheduledTasks
    var remainingCandidates = math.max(0, cellState.numberOfMachinesOn - scheduler.numMachinesBlackList).toInt
    var numTries =0
    val makespanLog = scala.collection.mutable.ListBuffer.empty[Double]
    //First approach: Initialization: Iterate over the tasks and choose any machine randomly and then we only apply the crossing thing between ALL machines in cluster.

    //Initialization
    var chosenMachines = scala.collection.mutable.ListBuffer.empty[Int]
    var scheduledTasks = 0
    var makespan = 0.0
    for(scheduledTasks <- 0 until job.numTasks){
      var scheduled = false
      while (!scheduled){
        val rnd = new scala.util.Random
        val range = 0 until cellState.numMachines
        val mID = (range(rnd.nextInt(range length)))
        val machineOcurrences = chosenMachines.count(_ == mID)
        if (cellState.isMachineOn(mID) && cellState.availableCpusPerMachine(mID) >= (job.cpusPerTask + (machineOcurrences * job.cpusPerTask) + 0.0001) && cellState.availableMemPerMachine(mID) >= (job.memPerTask +  + (machineOcurrences * job.memPerTask) + 0.0001)) {
          assert(cellState.isMachineOn(mID), "Trying to pick a powered off machine with picker : "+name)
          chosenMachines += mID
          if (job.taskDuration * cellState.machinesPerformance(mID) > makespan){
            makespan = job.taskDuration * cellState.machinesPerformance(mID)
          }
          scheduled = true
        }
        else{
          numTries+=1
        }
      }
    }
    makespanLog += makespan
  //After this, we should have an array of machines that will host our tasks. We will then cross the worst machine with a random one

    for(epoch <- 0 until 500){
      var worstMakespan = 0.0
      var worstParent = -1

      for (chosenMachine <- chosenMachines) {
        //Using taskDuration and makespan only for illustrating purposes, not necessary. We could make it with the performance due to the equality in task duration
        if(cellState.machinesPerformance(chosenMachine) *job.taskDuration > worstMakespan){
          worstMakespan = cellState.machinesPerformance(chosenMachine) *job.taskDuration
          worstParent = chosenMachine
        }
      }
      //Now we have the worst parent, we should exchange it for another.
      var scheduled = false
      while (!scheduled){
        val rnd = new scala.util.Random
        val range = 0 until cellState.numMachines
        val mID = (range(rnd.nextInt(range length)))
        val machineOcurrences = chosenMachines.count(_ == mID)
        if (cellState.isMachineOn(mID) && cellState.availableCpusPerMachine(mID) >= (job.cpusPerTask + (machineOcurrences * job.cpusPerTask) + 0.0001) && cellState.availableMemPerMachine(mID) >= (job.memPerTask +  + (machineOcurrences * job.memPerTask) + 0.0001)) {
          assert(cellState.isMachineOn(mID), "Trying to pick a powered off machine with picker : "+name)
          chosenMachines -= worstParent
          chosenMachines += mID
          scheduled = true
        }
        else{
          numTries+=1
        }
      }

      //Only for testing purposes
      var checkMespan = 0.0
      for (chosenMachine <- chosenMachines) {
        if (job.taskDuration * cellState.machinesPerformance(chosenMachine) > checkMespan) {
          checkMespan = job.taskDuration * cellState.machinesPerformance(chosenMachine)
        }
      }
      makespanLog += checkMespan
      //End of testing purposes
    }
    for(claimMachine <- chosenMachines){
      assert(claimMachine >= 0 && claimMachine < cellState.machineSeqNums.length)
      val claimDelta = new ClaimDelta(scheduler,
        claimMachine,
        cellState.machineSeqNums(claimMachine),
        if (cellState.machinesHeterogeneous && job.workloadName == "Batch") job.taskDuration * cellState.machinesPerformance(claimMachine) else job.taskDuration,
        job.cpusPerTask,
        job.memPerTask,
        job = job)
      claimDelta.apply(cellState = cellState, locked = false)
      claimDeltas += claimDelta
    }
    //At the end of the epochs, we return de applied scheduling
    claimDeltas
  }

  override def pickResource(cellState: CellState, job: Job, candidatePool: IndexedSeq[Int], remainingCandidates: Int) = {
    //YOU DON'T CALL THIS
    var machineID = -1
    var numTries =0
    var remainingCandidatesVar= cellState.numMachines
    val loop = new Breaks;

    //initialization

    loop.breakable {
      for(i <-  cellState.numMachines-1 to 0 by -1){
        //FIXME: Putting a security margin of 0.01 because mesos is causing conflicts
        val mID = cellState.machinesLoad(i)
        if (cellState.isMachineOn(mID) && cellState.availableCpusPerMachine(mID) >= (job.cpusPerTask + 0.0001) && cellState.availableMemPerMachine(mID) >= (job.memPerTask + 0.0001)) {
          machineID=mID
          assert(cellState.isMachineOn(machineID), "Trying to pick a powered off machine with picker : "+name)
          loop.break;
        }
        else{
          numTries+=1
          remainingCandidatesVar -=1 // This is irrelevant in this implementation, as derivable of numTries. I'll use it in quicksort-like implementations
        }

      }
    }

    new Tuple4(machineID, numTries, remainingCandidatesVar, candidatePool)
  }
  override val name: String = "reverse-power-picker-candidate"

}