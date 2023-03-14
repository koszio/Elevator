package elevator.demo;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import elevator.Elevators;
import elevator.rmi.Elevator;
import elevator.rmi.MakeAll;

public class ElevatorHandler extends Thread {
    Elevator elevator;
    int elevatorIndex;

    int destinationFloor = 0;
    int direction = 0; // Current direction of the floor
    int lastStop = -1; // Last visited floor of the current elevator.

    double currentPosition; // Current position of elevator
    boolean isStopped = false; // Boolean variable to check stop button
    boolean isJobFinished = false; // Used to keep track if there is any ongoing jobs that are not done

    // Used for calculating cost of adding a new job (floor job)
    int currentDirectionJobsCount = 0;
    int oppositeDirectionJobsCount = 0;
    int otherDirectionJobsCount = 0;

    // A list to put the available job requests
    Job currentJob;
    ArrayList<Job> jobList = new ArrayList<Job>();

    // Used to signal when a new job has been added and elevator should wake up
    ReentrantLock lock = new ReentrantLock();
    Condition not_Empty_List_Condition_Variable = lock.newCondition();

    // Constants used for direction, to make code more readable
    static final int DIRECTION_STILL = 0;
    static final int DIRECTION_UP = 1;
    static final int DIRECTION_DOWN = -1;

    public ElevatorHandler(Elevator elevator, int elevatorIndex) {
        this.elevator = elevator;
        this.elevatorIndex = elevatorIndex;
        this.setName("Elevator " + elevatorIndex);
    }

    @Override
    public void run() {
        super.run();

        try {
            while (true) {

                // If the list is empty wait on the not_Empty_List_Condition_Variable Condition
                // be true
                if (jobList.isEmpty()) {
                    // Waiting for being notified ...
                    synchronized (not_Empty_List_Condition_Variable) {
                        not_Empty_List_Condition_Variable.wait();
                    }

                    // When signaled/notified procceed by executing the
                    // first assigned job
                    isJobFinished = false;
                    currentJob = jobList.get(0);
                    performJob(currentJob);
                }
                // If the currentjob== null and there are still tasks in the list, perform the
                // first non null job
                // This is because after the first time a job will be exectured the variable
                // currentJob will be made equal to null
                // However if there are still jobs in the list then the next available must be
                // executed.
                else if (currentJob == null) {
                    currentJob = jobList.get(0);
                    performJob(currentJob);
                    isJobFinished = false;
                }

                // Get the current pos of the elevator
                currentPosition = elevator.whereIs();

                elevator.setScalePosition((int) (currentPosition + 0.5));
                // An If statement to check if the elevator has arrived at its destination
                // and its task(job) is not yet declared as done
                if (isAtDestination(currentPosition) && isJobFinished == false) {

                    lastStop = destinationFloor;
                    // Stop the elevator, open the door and remove the job from the list
                    elevator.stop();
                    elevator.open();
                    jobList.remove(currentJob);

                    // make current job null and mark as done
                    currentJob = null;
                    isJobFinished = true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // In that method it is checked where the elevator is in respect to its
    // destination point

    private boolean isAtDestination(double position) {
        // If elevator is moving upwards and has passed by the destination then
        // returntrue
        if (direction == DIRECTION_UP && position >= (double) destinationFloor) {
            System.out.println("Elevator " + elevatorIndex + " has stopped at floor: " + position + " Destination: "
                    + destinationFloor + " Direction:" + ((direction == DIRECTION_DOWN) ? "down" : "up"));
            System.out.println(jobList + "\n");
            return true;
        }

        // If elevator is moving downwards and has passed by the destination then return
        // true
        else if (direction == DIRECTION_DOWN && position <= (double) destinationFloor) {
            System.out.println("Elevator " + elevatorIndex + " has stopped at floor: " + position + " Destination: "
                    + destinationFloor + " Direction: " + ((direction == DIRECTION_DOWN) ? "down" : "up"));
            System.out.println(jobList + "\n");
            return true;
        }
        return false;
    }

    // Starts the elevator and sets its direction
    public void getDirection() {
        try {

            if (currentPosition >= (double) destinationFloor) {
                elevator.down();
                direction = DIRECTION_DOWN;
                System.out.println("Elevator " + elevatorIndex + " going down, pos: " + elevator.whereIs() + " dest: "
                        + destinationFloor);
            }

            else if (currentPosition <= (double) destinationFloor) {
                elevator.up();
                direction = DIRECTION_UP;
                System.out.println("Elevator " + elevatorIndex + " going up, pos: " + elevator.whereIs() + " dest: "
                        + destinationFloor);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    // A job is added to the list and an elevator is awaken
    public void addJob(Job job) {
        boolean isDuplicate = false;

        // Duplicates control
        for (Job jobEntry : jobList) {
            if (jobEntry.destinationFloor == job.destinationFloor && job.direction == jobEntry.direction) {
                isDuplicate = true;
                break;
            }
        }

        // If there is not other similar job then it is eligible to be added in the list
        if (isDuplicate == false) {
            jobList.add(job);

            // Rearrange the list
            jobList = prioritizeJobs(jobList);
            System.out.println("Elevator " + elevatorIndex + " added job to list: " + job.toString());
            System.out.println(jobList + "\n");

            // Notify elevator that there is a new job available
            synchronized (not_Empty_List_Condition_Variable) {
                not_Empty_List_Condition_Variable.notify();
            }
            // Set current job to the new first prioritized job so the elevator gets its
            // target
            // updated even if it is already moving

            currentJob = jobList.get(0);
            destinationFloor = currentJob.destinationFloor;
        }
    }

    public void performJob(Job job) {
        destinationFloor = job.destinationFloor;

        if (lastStop != destinationFloor) {
            try {

                Thread.sleep(1000);
                elevator.close();
                Thread.sleep(4 * (long) (Elevators.step / MakeAll.getVelocity()));
            } catch (Exception e) {
                e.printStackTrace();
            }
            // Elevator begin to move in the appropriate direction
            getDirection();
        }
    }

    // The cost method is used to calculate the cost of a given cost. Based on that
    // the elevator that has the minimum
    // value will be assigned to perform the task
    public double getCost(Job job) {
        try {
            // Create a copy of the list with tasks because we do not need to change it
            // while the elevators are performing tasks
            ArrayList<Job> jobs = new ArrayList<Job>(jobList);

            // Find the distance to the end of tasks before adding a new one.
            // This will needed to calculate the total extra distance

            double distanceToEndBeforeJob = getDistanceToJob(jobs, jobs.size() - 1);
            jobs.add(job);
            jobs = prioritizeJobs(jobs);

            // Find the distance to the end of tasks after adding a new one
            // This will be needed to calculate total extra distance
            double distanceToEndAfterJob = getDistanceToJob(jobs, jobs.size() - 1);

            // An index of the task in the joblist which practically is how many stops
            // before the new job
            int indexOfJob = jobs.indexOf(job);
            // How many floors does the elevator need to pass before the new job
            double distanceToJob = getDistanceToJob(jobs, indexOfJob);
            // If this job is the only job in the joblist, use distance to floor instead
            if (distanceToJob == 0) {
                distanceToJob = Math.abs(elevator.whereIs() - job.destinationFloor);
            }
            // Calculations of time with the elevator velocity in mind (Pretty much exact
            // milliseconds to job)
            double velocity = MakeAll.getVelocity();
            double tickLength = Elevators.step / velocity;
            int stopDelay = (int) (indexOfJob * (4 * tickLength + 1000));
            int travelDelay = (int) (distanceToJob * (25 * tickLength));

            // If the elevator was already stopping at the new jobs floor, remove the extra
            // stopping time from calculation. (It was added twice otherwise)
            if (indexOfJob > 0 && indexOfJob != (jobs.size() - 1)) {
                if (jobs.get(indexOfJob - 1).destinationFloor == job.destinationFloor
                        || jobs.get(indexOfJob + 1).destinationFloor == job.destinationFloor) {
                    stopDelay -= (4 * tickLength + 1000);
                }
            }

            // Number of delayed jobs and correpsonding delay time is calculated
            int numDelayedJobs = (jobs.size() - 1) - indexOfJob;
            double delayTime = numDelayedJobs * (4 * tickLength + 1000);

            // Extra distance is calculated. If calculation gives 0, then this is the first
            // job
            // and the extra distance then is the same as distance to job.
            double extraDistance = (distanceToEndAfterJob - distanceToEndBeforeJob);
            if (indexOfJob == 0) {
                extraDistance = distanceToJob;
            }

            System.out.println("Cost info for elevator " + elevatorIndex + ": Stoptime " + stopDelay + ", Traveltime: "
                    + travelDelay);
            System.out.println("Extra distance: " + extraDistance + " DelayTime: " + delayTime);
            System.out.println("Total cost for elevator " + elevatorIndex + ": "
                    + (stopDelay + travelDelay + delayTime) * extraDistance);

            return (stopDelay + travelDelay + delayTime) * extraDistance;
        } catch (Exception e) {
            e.printStackTrace();
            return Integer.MAX_VALUE;
        }
    }

    // Returns the distance to the job in number of floors to travel.
    public double getDistanceToJob(ArrayList<Job> jobs, int indexOfJob) {
        try {
            // If there are any jobs in the list
            if (jobs.size() > 0) {
                double currentDirectionJobsLength = 0;
                double oppositeDirectionJobsLength = 0;
                double otherDirectionJobsLength = 0;
                boolean foundJob = false;
                double position = elevator.whereIs();
                // Get distance of current direction before highest/lowest floor
                if (currentDirectionJobsCount > 0) {
                    int endIndex = currentDirectionJobsCount - 1;
                    if (indexOfJob < endIndex) {
                        endIndex = indexOfJob;
                        foundJob = true;
                    }
                    currentDirectionJobsLength = Math
                            .abs(position - jobs.get(endIndex).destinationFloor);
                }
                // Get distance of opposite direction, when the elevator turned around, if job
                // was not passed yet
                if (oppositeDirectionJobsCount > 0 && foundJob == false) {
                    int startIndex = 0;
                    if (currentDirectionJobsCount > 0) {
                        startIndex = currentDirectionJobsCount - 1;
                    }

                    int endIndex = currentDirectionJobsCount + oppositeDirectionJobsCount - 1;
                    // Endindex is indexOfJob if job is in this section of the joblist
                    if (indexOfJob < endIndex) {
                        endIndex = indexOfJob;
                        foundJob = true;
                    }
                    // If there was a job in the current direction, then calculate from first to
                    // last job in opposite direction
                    if (currentDirectionJobsLength > 0) {
                        oppositeDirectionJobsLength = Math
                                .abs(jobs.get(startIndex).destinationFloor - jobs.get(endIndex).destinationFloor);
                    }
                    // Otherwise calculate from current position
                    else {
                        oppositeDirectionJobsLength = Math.abs(position - jobs.get(endIndex).destinationFloor);
                    }
                }
                // Get distance of other direction, after turning around and serving the floors
                // that
                // the elevator already has passed in the first direction, if job was not passed
                // yet
                if (otherDirectionJobsCount > 0 && foundJob == false) {
                    int startIndex = 0;
                    if (currentDirectionJobsCount > 0 || oppositeDirectionJobsCount > 0) {
                        startIndex = currentDirectionJobsCount + oppositeDirectionJobsCount - 1;
                    }

                    int endIndex = currentDirectionJobsCount + oppositeDirectionJobsCount + otherDirectionJobsCount - 1;
                    // Endindex is indexOfJob if job is in this section of the joblist
                    if (indexOfJob < endIndex) {
                        endIndex = indexOfJob;
                    }
                    // If there was a job in the current or opposite direction, then calculate from
                    // first to last job in other direction
                    if (currentDirectionJobsLength > 0 || oppositeDirectionJobsLength > 0) {
                        otherDirectionJobsLength = Math
                                .abs(jobs.get(startIndex).destinationFloor - jobs.get(endIndex).destinationFloor);
                    }
                    // Otherwise from current position
                    else {
                        otherDirectionJobsLength = Math.abs(position - jobs.get(endIndex).destinationFloor);
                    }
                }
                return (currentDirectionJobsLength + oppositeDirectionJobsLength + otherDirectionJobsLength);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public ArrayList<Job> prioritizeJobs(ArrayList<Job> jobs) {
        ArrayList<Job> currentDirectionJobs = new ArrayList<Job>();
        ArrayList<Job> oppositeDirectionJobs = new ArrayList<Job>();
        ArrayList<Job> otherDirectionJobs = new ArrayList<Job>();

        for (Job job : jobs) {
            try {
                if (job.direction == 0) {
                    if (direction == DIRECTION_UP && (double) job.destinationFloor > elevator.whereIs()) {
                        currentDirectionJobs.add(job);
                    } else if (direction == DIRECTION_UP) {
                        oppositeDirectionJobs.add(job);
                    } else if (direction == DIRECTION_DOWN && (double) job.destinationFloor < elevator.whereIs()) {
                        currentDirectionJobs.add(job);
                    } else if (direction == DIRECTION_DOWN) {
                        oppositeDirectionJobs.add(job);
                    } else if (direction == DIRECTION_STILL) {
                        currentDirectionJobs.add(job);
                    }
                } else {
                    if (direction == job.direction || direction == DIRECTION_STILL) {
                        if (direction == DIRECTION_UP && (double) job.destinationFloor > elevator.whereIs()) {
                            currentDirectionJobs.add(job);
                        } else if (direction == DIRECTION_DOWN && (double) job.destinationFloor < elevator.whereIs()) {
                            currentDirectionJobs.add(job);
                        } else if (direction == DIRECTION_STILL) {
                            currentDirectionJobs.add(job);
                        } else {
                            otherDirectionJobs.add(job);
                        }
                    } else {
                        oppositeDirectionJobs.add(job);
                    }
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        if (direction == DIRECTION_UP || direction == DIRECTION_STILL) {
            Collections.sort(currentDirectionJobs, new Job.JobComparatorByFloorAscending());
            Collections.sort(oppositeDirectionJobs, new Job.JobComparatorByFloorDescending());
            Collections.sort(otherDirectionJobs, new Job.JobComparatorByFloorAscending());
        } else if (direction == DIRECTION_DOWN) {
            Collections.sort(currentDirectionJobs, new Job.JobComparatorByFloorDescending());
            Collections.sort(oppositeDirectionJobs, new Job.JobComparatorByFloorAscending());
            Collections.sort(otherDirectionJobs, new Job.JobComparatorByFloorDescending());
        }
        currentDirectionJobsCount = currentDirectionJobs.size();
        oppositeDirectionJobsCount = oppositeDirectionJobs.size();
        otherDirectionJobsCount = otherDirectionJobs.size();

        currentDirectionJobs.addAll(oppositeDirectionJobs);
        currentDirectionJobs.addAll(otherDirectionJobs);
        return currentDirectionJobs;
    }

    public void toggleStop() {
        try {
            if (isStopped) {
                if (direction == DIRECTION_UP) {
                    elevator.up();
                } else if (direction == DIRECTION_DOWN) {
                    elevator.down();
                }
                isStopped = false;
            } else {
                elevator.stop();
                isStopped = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}