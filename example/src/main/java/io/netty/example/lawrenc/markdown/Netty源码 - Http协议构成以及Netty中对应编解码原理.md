

- 常用的lock 是一般都是通过时间换空间的做法。

- JDK的ThreadLocal 是典型的通过空间换时间的做法
- 解决hash冲突常见方法
  - 开放地址法（ThreadLocalMap ）（线性探测再散列、二次探测再散列、伪随机探测再散列）（当当前位置出现hash冲突就寻找下一个空的散列地址）
    1. 容易产生堆积问题，不适于大规模的数据存储。
    2. 散列函数的设计对冲突会有很大的影响，插入时可能会出现多次冲突的现象。
    3. 删除的元素是多个冲突元素中的一个，需要对后面的元素作处理，实现较复杂。
  - 链地址法（hashmap）
    1. 处理冲突简单，且无堆积现象，平均查找长度短。
    2. 链表中的结点是动态申请的，适合构造表不能确定长度的情况。
    3. 删除结点的操作易于实现。只要简单地删去链表上相应的结点即可。
    4. 指针需要额外的空间，故当结点规模较小时，开放定址法较为节省空间。
  - rehash
  - 建立公共溢出区
- ThreadLocalMap 采用开放地址法原因
  1. ThreadLocal 中看到一个属性 HASH_INCREMENT = 0x61c88647 ，0x61c88647 是一个神奇的数字，让哈希码能均匀的分布在2的N次方的数组里, 即 Entry[] table，关于这个神奇的数字google 有很多解析，这里就不重复说了
  2. ThreadLocal 往往存放的数据量不会特别大（而且key 是弱引用又会被垃圾回收，及时让数据量更小），这个时候开放地址法简单的结构会显得更省空间，同时数组的查询效率也是非常高，加上第一点的保障，冲突概率也低
- 缓存行
  - CPU缓存，L1,L2,L3
  - 伪共享产生和解决方案
  - JDK的@Contended
  - 线程的栈空间是否是挂在CPU的一个核心（多核）上
- Netty中对于FTL的大量使用

## JDK的ThreadLocal源码

### 过一遍源码

为了查看源码方便，创建一个简单的ThreadLocal使用demo

```java
@Slf4j
public class ThreadLocalTest {
    ThreadLocal<Integer> local1 = ThreadLocal.withInitial(() -> 1024);
    ThreadLocal<Integer> local2 = new ThreadLocal<>() {
        @Override
        protected Integer initialValue() {
            return 28;
        }
    };

    volatile boolean stop = false;

    @Test
    public void threadLocal() throws Exception {

        log.info("########################################");
        log.info("local1 init:{}", local1.get());
        log.info("local2 init:{}", local2.get());
        Thread thread = new Thread(() -> {
            int count = 0;
            while (true) {

                LockSupport.park();
                log.info("local1 value({}):{}", ++count, local1.get());
                log.info("local2 value({}):{}", count, local2.get());
                if (stop) {
                    return;
                }
            }
        });
        thread.start();

        LockSupport.unpark(thread);

        TimeUnit.SECONDS.sleep(2);

        log.info("change local value");
        local1.set(9527);
        local2.set(2145);
        stop = true;
        LockSupport.unpark(thread);

        thread.join();
        log.info("########################################");
        local1.remove();
        local2.remove();
    }
}
```

输出

```java
22:15:11.059 [main] INFO  i.n.e.lawrenc.netty.ThreadLocalTest - ########################################
22:15:11.062 [main] INFO  i.n.e.lawrenc.netty.ThreadLocalTest - local1 init:1024
22:15:11.063 [main] INFO  i.n.e.lawrenc.netty.ThreadLocalTest - local2 init:28
22:15:11.064 [Thread-0] INFO  i.n.e.lawrenc.netty.ThreadLocalTest - local1 value(1):1024
22:15:11.064 [Thread-0] INFO  i.n.e.lawrenc.netty.ThreadLocalTest - local2 value(1):28
22:15:13.065 [main] INFO  i.n.e.lawrenc.netty.ThreadLocalTest - change local value
22:15:13.065 [Thread-0] INFO  i.n.e.lawrenc.netty.ThreadLocalTest - local1 value(2):1024
22:15:13.065 [Thread-0] INFO  i.n.e.lawrenc.netty.ThreadLocalTest - local2 value(2):28
22:15:13.065 [main] INFO  i.n.e.lawrenc.netty.ThreadLocalTest - ########################################
    
```

##### ThreadLocal#set

- ThreadLocal#set

  ```java
  public void set(T value) {
      Thread t = Thread.currentThread();
      ThreadLocalMap map = getMap(t);
      if (map != null) {
          map.set(this, value);
      } else {
          createMap(t, value);
      }
  }
  
  ThreadLocalMap getMap(Thread t) {
      return t.threadLocals;
  }
  ```

  首先获取当前线程对象，再根据线程对象获取ThreadLocalMap，该值是线程的一个成员变量

  ```java
  public class Thread implements Runnable {
      /* ThreadLocal values pertaining to this thread. This map is maintained
       * by the ThreadLocal class. */
      ThreadLocal.ThreadLocalMap threadLocals = null;
  
      /*
       * InheritableThreadLocal values pertaining to this thread. This map is
       * maintained by the InheritableThreadLocal class.
       */
      ThreadLocal.ThreadLocalMap inheritableThreadLocals = null;
  }
  ```

  inheritableThreadLocals这个变量也是ThreadLocalMap，配合InheritableThreadLocal使用，稍后会说到。

  可以看见首次调用set时，获取到的map是null，因此会进入 createMap(t, value);方法

  ```java
  void createMap(Thread t, T firstValue) {
      t.threadLocals = new ThreadLocalMap(this, firstValue);
  }
  ```

  这里会创建一个ThreadLocalMap给线程变量threadLocals赋值

  ```java
  ThreadLocalMap(ThreadLocal<?> firstKey, Object firstValue) {
      table = new Entry[INITIAL_CAPACITY];
      int i = firstKey.threadLocalHashCode & (INITIAL_CAPACITY - 1);
      table[i] = new Entry(firstKey, firstValue);
      size = 1;
      setThreshold(INITIAL_CAPACITY);
  }
  ```

  ThreadLocalMap里面维护了一个Entry数组table，即是给table这个数组初始化，并设值

  ```java
  static class ThreadLocalMap {
  
      static class Entry extends WeakReference<ThreadLocal<?>> {
          /** The value associated with this ThreadLocal. */
          Object value;
  
          Entry(ThreadLocal<?> k, Object v) {
              super(k);
              value = v;
          }
      }
      private static final int INITIAL_CAPACITY = 16;
  
      private Entry[] table;
  }
  ```

  值得注意的是Entry是由key，value构成的，且key是一个ThreadLocal的弱引用（当没有强引用指向弱引用时，发生gc会立即回收弱引用对象）。

  其实到此为止第一次赋值就结束了，当我们向ThreadLocal对象里面存入了一个值时，该值和线程Thread绑定，而Thread又和内部成员变量ThreadLocalMap绑定，ThreadLocalMap内部存储了一个Entry数组，Entry是由弱引用（即当前ThreadLocal对象）作为key，我们存入的值作为value。各个依赖关系如下:

  ![image](https://lmy25.wang/upload/2020/08/image-fad4a861e373441d8755fcbbb80625f0.png)

  当在同一个线程第二次设值时，此时线程的map不为空，则会直接进入set方法

  ```java
  map.set(this, value);
  ```

  ThreadLocalMap#set具体如下

  ```java
   private void set(ThreadLocal<?> key, Object value) {
       Entry[] tab = table;
       int len = tab.length;
       //计算当前value应该存放的位置
       int i = key.threadLocalHashCode & (len-1);
  
       //若是当前位置已经存在元素，则逐步搜索（链寻址），直到元素为空就结束循环
       for (Entry e = tab[i]; e != null; e = tab[i = nextIndex(i, len)]) {
           ThreadLocal<?> k = e.get();
  		  //当有相同key，则直接更新为新的value值
           if (k == key) {
               e.value = value;
               return;
           }
  		//当key为null时代表该threadLocal被gc回收了，此时做清理相关操作
           if (k == null) {
               //探测式清理
               replaceStaleEntry(key, value, i);
               return;
           }
       }
  	//在for循环中没返回，证明在当前i处entry是为null的，因此直接进行赋值
       tab[i] = new Entry(key, value);
       int sz = ++size;
       //启发式清理之后判断是否需要扩容
       if (!cleanSomeSlots(i, sz) && sz >= threshold)
           rehash();
   }
  ```

  set方法有几个需要注意的地方

  1.  计算索引i的时候使用到了魔数0x61c88647（1640531527），散列程度高，最后有测试。

     ```java
     int i = key.threadLocalHashCode & (len-1);
     
     private final int threadLocalHashCode = nextHashCode();
     //魔数
     private static final int HASH_INCREMENT = 0x61c88647;
     private static int nextHashCode() {
         return nextHashCode.getAndAdd(HASH_INCREMENT);
     }
     ```

  2. 什么时候key才会被gc回收，即什么时候会满足如下条件呢

     ```java
     if (k == null) {
         replaceStaleEntry(key, value, i);
         return;
     }
     ```

     我们知道ThreadLocalMap的key为threadLocal弱引用对象，当我们创建一个ThreadLocal时至少有两个引用指向当前的threadLocal对象，一个是new的强引用，一个就是ThreadLocalMap中的弱引用。当我们使用完一个threadLocal对象之后手动取消强引用，即threadLocal=null，假若没有其他强引用，在下次gc发生时，我们所创建的ThreadLocal对象将被回收，从而导致当前线程的threadLocalMap对象里面的key会存在为null的情况。当然，我们没取消threadLocal对象的强引用会导致map中的key、value一直存在。假若ThreadLocalMap中的key不使用弱引用，且我们使用的线程没有被销毁（线程池），我们手动将我们创建的local对象设为null，我们创建的threadLocal对象仍然会一直存在强引用（在ThreadLocalMap的key中），这就很可能会发生内存泄漏。当然有了弱引用我们没有取消local对象的强引用也可能会导致内存泄漏。

  3. nextIndex和preIndex方法都是环形搜索

     ```java
     private static int nextIndex(int i, int len) {
         return ((i + 1 < len) ? i + 1 : 0);
     }
     private static int prevIndex(int i, int len) {
         return ((i - 1 >= 0) ? i - 1 : len - 1);
     }
     ```

  4. 还有就是当出现冲突时，会使用开放式探测的方法向下寻找为null的entry，找到之后赋值。

  set方法主要分为三种情况

  - 进入for循环没有返回，或者是没有进入for循环

    1. 若是进入for循环没有返回说明在整个entry数组中没有空位了（**这种情况实际是不存在的**）

    2. 没有进入for循环则说明当前索引i处的entry就为null，则直接进行赋值，且size递增

       ```java
       tab[i] = new Entry(key, value);
       int sz = ++size;
       if (!cleanSomeSlots(i, sz) && sz >= threshold)
           rehash();
       ```

       在完成元素的添加之后会进行一次启发式清理，即调用cleanSomeSlots(i, sz)，当启发式清理了元素（至少清理一个元素）则会返回false，进而就不会进行扩容，若是启发式清理没有清理元素，则会根据当前size和threshold判断是否需要扩容，而threshold的初始化是在创建ThreadLocalMap时设定的，可以看见扩容阈值为len的2/3，即为10。

       ```java
       ThreadLocalMap(ThreadLocal<?> firstKey, Object firstValue) {
           table = new Entry[INITIAL_CAPACITY];
           int i = firstKey.threadLocalHashCode & (INITIAL_CAPACITY - 1);
           table[i] = new Entry(firstKey, firstValue);
           size = 1;
           setThreshold(INITIAL_CAPACITY);
       }
       
       private void setThreshold(int len) {
           threshold = len * 2 / 3;
       }
       
       /**
         * The initial capacity -- MUST be a power of two.
         */
       private static final int INITIAL_CAPACITY = 16;
       ```

       进入扩容方法rehash

       ```java
       private void rehash() {
           //会进行一次清理之后再判断是否需要扩容
           expungeStaleEntries();
       
           // Use lower threshold for doubling to avoid hysteresis
           if (size >= threshold - threshold / 4)
               resize();
       }
       ```

       进入expungeStaleEntries方法

       ```java
       private void expungeStaleEntries() {
           Entry[] tab = table;
           int len = tab.length;
           for (int j = 0; j < len; j++) {
               Entry e = tab[j];
               if (e != null && e.get() == null)
                   //探测式清理
                   expungeStaleEntry(j);
           }
       }
       ```

       从Entry数组的起始位置开始查找，当发现有Entry的key==null时（e是继承了WeakReference<ThreadLocal<?>>的，因此get（）返回的则是对应得弱引用key，若不存在则代表gc回收了），就触发探测式清理。

       ```java
       private int expungeStaleEntry(int staleSlot) {
           Entry[] tab = table;
           int len = tab.length;
       
           // expunge entry at staleSlot
           //先将当前位置设为null，便于回收
           tab[staleSlot].value = null;
           tab[staleSlot] = null;
           size--;
       
           // Rehash until we encounter null
           //接下来从此处开始寻找是否有发生过hash冲突的，需要rehash的，并顺便清理其余被gc回收的数据
           Entry e;
           int i;
           for (i = nextIndex(staleSlot, len);
                (e = tab[i]) != null;
                i = nextIndex(i, len)) {
               ThreadLocal<?> k = e.get();
               if (k == null) {
                   e.value = null;
                   tab[i] = null;
                   size--;
               } else {
                  
                   int h = k.threadLocalHashCode & (len - 1);
                    //表明该值是出现过hash冲突的
                   if (h != i) {
                       //当前位置设为null空槽，便于gc
                       tab[i] = null;
       
                       // Unlike Knuth 6.4 Algorithm R, we must scan until
                       // null because multiple entries could have been stale.
                       //继续向后开放寻址，直到找到为null的entry节点
                       while (tab[h] != null)
                           h = nextIndex(h, len);
                       //将之前i处的元素赋值给距离正确索引h最近的一个空槽上（目的也是为了防止内存泄漏）
                       tab[h] = e;
                   }
               }
           }
           return i;
       }
       ```

       探测式清理的入参为key==null的索引。一次探测式清理的示意图如下（**key相同仅代表hash冲突了，值并不同**）

       ![image](https://lmy25.wang/upload/2020/08/image-41f8bf55de564383b974687595312501.png)

       可以试想下，假设不重新计算hash值，判断是否发生过冲突，并且重新寻址会发生什么？

       进行一次探测式清理之后槽位分布如下

       ![image](https://lmy25.wang/upload/2020/08/image-94f83235b1224c7c9dcbfc304ccf0109.png)

       假设再次插入一个值，该数据和index=6的key相同，则计算出来的索引位置也在k2，由于k2的索引位置在index=3处，则开放寻址会定位到index=5的地方，在index=5插入新数据，此时会出现index=5和index=6处的key相同，但是占用了两个槽位，且在index=6处的槽位永远不会回收，从而造成内存泄漏。

  - 进入for循环，满足 if (k == key)

    这种情况比较简单，当key相同，说明是在同一个线程中重复赋值，则直接覆盖value即可。

  - 进入for循环，满足k == null

    若key==null，则证明该key被gc回收了。当我们使用完threadlocal变量之后，将其废弃设为null，则new出来的强引用就没了，剩下的只有Entry中的弱引用key，只要发生gc，该key所对应的的threadlocal就会被回收。

    当key==null会先处理失效的key此处的entry。便于gc回收

    ```java
    //staleSlot为失效key的槽位位置
    private void replaceStaleEntry(ThreadLocal<?> key, Object value,
                                   int staleSlot) {
        Entry[] tab = table;
        int len = tab.length;
        Entry e;
        int slotToExpunge = staleSlot;
        //1.向前搜索
        for (int i = prevIndex(staleSlot, len); (e = tab[i]) != null;  i = prevIndex(i, len))
            if (e.get() == null)
                slotToExpunge = i;
    
        //2.向后搜索
        for (int i = nextIndex(staleSlot, len); (e = tab[i]) != null;  i = nextIndex(i, len)) {
            ThreadLocal<?> k = e.get();
    
            // 找到相同的key，则覆盖value，之后再清理
            if (k == key) {
                e.value = value;
    
                tab[i] = tab[staleSlot];
                tab[staleSlot] = e;
    
                // 判断是否存在其余被回收的key
                if (slotToExpunge == staleSlot)
                    slotToExpunge = i;
                //先完成全量的探测式清理，再完成启发式清理
                cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
                return;
            }
    
            if (k == null && slotToExpunge == staleSlot)
                slotToExpunge = i;
        }
    
        //3.如果以上没有赋值成功，则将当前被回收key处的槽位重新赋新值
        tab[staleSlot].value = null;
        tab[staleSlot] = new Entry(key, value);
    
        //先完成全量的探测式清理，再完成启发式清理
        // If there are any other stale entries in run, expunge them
        if (slotToExpunge != staleSlot)
            cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
    }
    ```

    以上大致分为三步

    1. 首先向前环形搜索，寻找entry的key为null的位置，即被回收的位置，找到之后将索引赋值给slotToExpunge。直到找到entry==null的位置才结束。

    2. 之后向后环形搜索，也是直到找到entry==null的位置才结束。在寻找过程中，若发现key有相同的（即第一次插入k，v的时候索引是staleSlot，但是staleSlot位置处原来有数据，因此插入的数据会开放寻址到当前位置，即i处插入），则**交换位置**。将当前位置i处的entry更改为已经被回收的staleSlot处的entry，将staleSlot处的entry更改为i处entry（这个很重要）。

       交换位置之后判断slotToExpunge == staleSlot，只有在前面寻找其余被回收entry的key的时候才会更改slotToExpunge 的值，因此当没有其余被回收的key时，当前if才满足，随后将slotToExpunge 的值更改为当前索引i（当前索引i已经和staleSlot交换了数据，i此处的数据时无效的，被回收了key的）

       最后进行一次全量的探测式清理，逻辑和之前的探测式清理同理，会进行rehash判断数据是否需要重新移动槽位。完成之后再进行一次启发式清理，逻辑如下

       ```java
       /**
        * 启发式地清理slot,
        * i对应entry是非无效（指向的ThreadLocal没被回收，或者entry本身为空）
        * n是用于控制控制扫描次数的
        * 正常情况下如果log n次扫描没有发现无效slot，函数就结束了
        * 但是如果发现了无效的slot，将n置为table的长度len，做一次连续段的清理
        * 再从下一个空的slot开始继续扫描
        * 
        * 这个函数有两处地方会被调用，一处是插入的时候可能会被调用，另外个是在替换无效slot的时候可能会被调用，
        * 区别是前者传入的n为元素个数，后者为table的容量
        */
       private boolean cleanSomeSlots(int i, int n) {
           boolean removed = false;
           Entry[] tab = table;
           int len = tab.length;
           do {
               // i在任何情况下自己都不会是一个无效slot(指向的ThreadLocal没被回收，或者entry本身为空)，所以从下一个开始判断
               i = nextIndex(i, len);
               Entry e = tab[i];
               if (e != null && e.get() == null) {
                   // 扩大扫描控制因子
                   n = len;
                   removed = true;
                    // 清理一个连续段
                   i = expungeStaleEntry(i);
               }
           } while ( (n >>>= 1) != 0);
           return removed;
       }
       ```

    3. 如果for循环没有结束返回，则证明新值没有插入成功，则将此staleSlot处赋值为新值，最后再判断下是否有其余被回收的槽存在，若有则执行清理。

    replaceStaleEntry()方法整体执行流程如下示意图:

    ![image](https://lmy25.wang/upload/2020/08/image-7f667dc51a854213b60fb7f565e98287.png)
    
    5. 可以看出来，threadlocal在每次set时都会检测是否需要清理，不过还是建议手动remove，以防内存泄漏。
    6. 

- ThreadLocal#get

  ```java
  public T get() {
      Thread t = Thread.currentThread();
      ThreadLocalMap map = getMap(t);
      if (map != null) {
          ThreadLocalMap.Entry e = map.getEntry(this);
          if (e != null) {
              @SuppressWarnings("unchecked")
              T result = (T)e.value;
              return result;
          }
      }
      return setInitialValue();
  }
  ```
  
  get方法就简单很多了，先拿到线程对于的map，如没有设过值，则设置初始值并返回
  
  ```java
  private T setInitialValue() {
      T value = initialValue();
      Thread t = Thread.currentThread();
      ThreadLocalMap map = getMap(t);
      if (map != null) {
          map.set(this, value);
      } else {
          createMap(t, value);
      }
      if (this instanceof TerminatingThreadLocal) {
          TerminatingThreadLocal.register((TerminatingThreadLocal<?>) this);
      }
      return value;
  }
  ```
  
- ThreadLocal#remove

  ```java
  public void remove() {
      ThreadLocalMap m = getMap(Thread.currentThread());
      if (m != null) {
          m.remove(this);
      }
  }
  ```

### 疑问

### 总结

## FastThreadLocal源码

## Netty中如何使用

和其他类一起构建起高并发基础

## 参考文章

[Netty-性能优化工具类之FastThreadLocal分析](https://xuanjian1992.top/2019/09/06/Netty-%E6%80%A7%E8%83%BD%E4%BC%98%E5%8C%96%E5%B7%A5%E5%85%B7%E7%B1%BB%E4%B9%8BFastThreadLocal%E5%88%86%E6%9E%90/)

[从CPU Cache出发彻底弄懂volatile/synchronized/cas机制](https://juejin.im/post/6844903779276439560#comment)

[FastThreadLocal吞吐量居然是ThreadLocal的3倍](https://juejin.im/post/6844903878870171662)（包含大量测试以及参考美团的cpu缓存文章）

[Netty In Action](https://search.jd.com/Search?keyword=netty%20in%20action&enc=utf-8&suggest=1.def.0.base&wq=Netty%20in&pvid=fca1efd55e0848c9a7e9fe29aaf4057e)



### 使用ftl地方

io.netty.buffer.PooledByteBufAllocator.PoolThreadLocalCache

![image-20201116225931847](https://lmy25.wang/Netty%E5%9B%BE%E5%BA%8A/netty%E4%BD%BF%E7%94%A8ftl1.png)

```
private final PoolThreadLocalCache threadCache;
```

---

![Recycler对象池中使用FTL](https://lmy25.wang/Netty%E5%9B%BE%E5%BA%8A/%20netty%E4%BD%BF%E7%94%A8ftl2.png)



## 附

- [ 计算对象大小](https://www.jianshu.com/p/9d729c9c94c4)

- [美团agent相关的动态追踪](https://tech.meituan.com/2019/02/28/java-dynamic-trace.html)
- [缓存行]( https://my.oschina.net/manmao/blog/804161?nocache=1534146640808)