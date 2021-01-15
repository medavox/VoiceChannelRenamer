import java.util.concurrent.BlockingQueue

/**Performs operations on input data concurrently.
 * Each instance of this class only supports running one operation at a time.
 * @author Adam Howard
 * @since 17/09/2018
 */
internal class ParallelProcess<In, Out> {
    fun finishWhenQueueIsEmpty(finish:Boolean = true):ParallelProcess<In, Out> {
        finishWhenQueueIsEmpty = finish
        return this
    }
    //private var workFunction: (input: In) -> Out?
    /**In Queue processing,
     * signal the worker threads to finish gracefully once the queue is empty,
     * instead of waiting for new elements to be added.*/
    private var finishWhenQueueIsEmpty:Boolean = false
    /**In queue processing, stop all worker threads immediately after finishing their current element,
     * regardless of the state of the queue.*/
    var threadsKillswitch:Boolean = false

    private val workerThreads:MutableSet<Thread> = mutableSetOf()
    private lateinit var outputInProgress:MutableList<Out?>//nullable because worker threads may fail to produce output
    private var _exceptions = mutableListOf<Throwable>()
    val exceptions:List<Throwable> get() = _exceptions
    private var isRunning = false
    /**Run this instance's worker repeatedly concurrently with the same input.
     * @param input the input to run on
     * @param workFunction the work to be done on the input by each worker thread
     * @param numberOfWorkerThreads the number of threads -- and the number of times to repeat -- running the workFunction
     * */
    @Throws(IllegalStateException::class)
    fun repeatOnInput(input: In, numberOfWorkerThreads: Int = 5, workFunction: (input: In) -> Out?) {
        if(isRunning) throw IllegalStateException("this instance is already running an operation.\n" +
                "Collect the data from this operation first, or use another instance.")
        outputInProgress = MutableList(numberOfWorkerThreads) {null}
        for(i in 0 until numberOfWorkerThreads) {
            val worker = Thread { outputInProgress.add(workFunction(input)) }
            workerThreads.add(worker)
            isRunning = true
            worker.start()
        }
    }

    /**Process an array in parallel, with one worker thread per array element.
     * @param input the array to iterate over concurrently.
     * @param workFunction the work to be done on each element in the array
     *
     * NOTE: Threads are expensive, so try not to use this with Arrays that have more than ~30 elements.*/
    @Throws(IllegalStateException::class)
    fun oneWorkerPerElement(input: Array<out In>, workFunction: (input: In) -> Out?) {
        if(isRunning) throw IllegalStateException("this instance is already running an operation.\n" +
                "Collect the data from this operation first, or use another instance.")
        outputInProgress = MutableList(input.size) {null}
        for(i in input.indices) {
            val worker = Thread { outputInProgress.add(workFunction(input[i])) }
            workerThreads.add(worker)
            isRunning = true
            worker.start()
        }
    }
    /**Process elements in a queue (that may still be added to at any time) using a pool of worker threads.
     * The number of worker threads is usually far fewer than the number of elements in the queue at any one time.
     * In other words: performs work parallely, but not TOO parallely
     * @param inputQueue the queue of elements to be processed
     * @param workFunction the work to be done on each element in the queue
     * @param numberOfWorkerThreads number of parallel worker threads
     * @param waitTime the time to wait between checks for new elements when the queue is empty
     * */
    @Throws(IllegalStateException::class)
    fun workerPoolOnMutableQueue(inputQueue: BlockingQueue<out In>,
                                 workFunction: (input: In) -> Out?,
                                 numberOfWorkerThreads: Int = 5,
                                 waitTime:Long=250
    ) {
        if(isRunning) throw IllegalStateException("this instance is already running an operation.\n" +
                "Collect the data from this operation first, or use another instance.")
        outputInProgress = mutableListOf<Out?>()//the output list is an as-yet unknown size,
        _exceptions.clear()
        //so just initialise it and let the workers add their elements.
        //let's hope Kotlin Lists are thread-safe!
        for(i in 1..numberOfWorkerThreads) {
            val worker = Thread {
                while (!threadsKillswitch) {
                    //spins until there is something in the queue
                    if (!inputQueue.isEmpty()) {
                        val next: In = inputQueue.remove()
                        outputInProgress.add(workFunction(next))
                        if (finishWhenQueueIsEmpty) { //while we're still working when we've been told to stop when the queue is empty,
                            // indicate the number of items left
                            //println("items left in queue:" + inputQueue.size)
                        }
                    } else if (finishWhenQueueIsEmpty) { //this thread's been told to exit when the download queue is empty
                        break
                    } else {  //wait before checking again, so as not to waste CPU cycles
                        try {
                            Thread.sleep(waitTime)
                        } catch (ie: InterruptedException) {
                            System.err.println("Can't a thread catch a few ms sleep around here?!")
                            ie.printStackTrace()
                        }
                    }
                }
            }
            worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, exception ->
                _exceptions.add(exception)

                println("OH NO! "+exception)
                //throw exception
            }
            workerThreads.add(worker)
            isRunning = true
            worker.start()
        }
    }

    /**Get the resulting output from the worker threads.
     * This is a blocking method; it only returns once all worker threads have finished.*/
    //@Throws(Exception::class)
    fun collectOutputWhenFinished(): List<Out?> {
        for(thread in workerThreads) {
            thread.join()
        }
        println("exceptions: ${_exceptions.size}")
        isRunning = false
        //get rid of null elements, convert from MutableSet<Out?> to List<Out>
/*        if(exceptions.isNotEmpty()) {
            throw exceptions.first()
        }*/
        return outputInProgress
    }

    fun reset():ParallelProcess<In, Out> {
        workerThreads.clear()
        threadsKillswitch = false
        _exceptions.clear()
        outputInProgress.clear()
        return this
    }
}