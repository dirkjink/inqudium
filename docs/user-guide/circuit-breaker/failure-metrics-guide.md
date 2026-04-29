## Choosing the Right FailureMetrics: A Decision Guide

The `FailureMetrics` strategy is the heart of your Circuit Breaker.
It determines how precisely and how quickly the system reacts to instability.
Use this guide to select the algorithm that best fits your traffic patterns and reliability requirements.

---

### 1. The Strategy Overview

| Algorithm                 | Mechanism                                                       | Best For                                                        |
|:--------------------------|:----------------------------------------------------------------|:----------------------------------------------------------------|
| **Time-Based Error Rate** | Tracks % of failures in a moving time window (e.g., 10s).       | **Standard Microservices** with fluctuating traffic.            |
| **Sliding Window**        | Tracks the last $N$ discrete calls regardless of time.          | **Low-traffic / Background jobs** where time is irrelevant.     |
| **Consecutive Failures**  | Trips after $N$ back-to-back failures.                          | **Critical dependencies** that must fail fast on total outage.  |
| **Leaky Bucket**          | Constant "leaking" (decay) of error levels over time.           | **High-performance systems** needing linear, predictable decay. |
| **EWMA (Continuous)**     | Mathematical exponential decay based on time.                   | **Complex environments** requiring smooth, weighted reactivity. |
| **Composite**             | Combines multiple strategies (e.g., Error Rate OR Consecutive). | **Advanced safety nets** (multi-layered protection).            |

---

### 2. Detailed Breakdown & Selection Criteria

#### **Time-Based Error Rate (Recommended Default)**

This strategy calculates the failure percentage over a fixed duration
(e.g., last 10 seconds). It requires a `minimumNumberOfCalls`
to prevent "false positives" during low-traffic periods.

* **Pick this if:** You have varying traffic loads and want the breaker
  to "self-heal" (forget errors) automatically as time passes.
* **Selection Metric:** Use a window of **10–30s** and a threshold of **50%**.

#### **Count-Based Sliding Window**

Keeps a circular buffer of the last $N$ results. If you set it to 100,
it only cares about the last 100 calls, even if they happened an hour ago.

* **Pick this if:** Your service receives very few requests (e.g., once every minute).
  Time-based windows would "expire" too fast to catch a pattern.
* **Selection Metric:** Use a window size of **10–50 calls**.

#### **Consecutive Failures**

The simplest approach: it only counts failures in a row. A single success resets the counter to zero.

* **Pick this if:** You are calling a database or a core infrastructure component
  where *any* three failures in a row indicate a total disaster.
* **Selection Metric:** Set a threshold of **3 to 5 failures**.

#### **Leaky Bucket**

Every failure adds "water" to a bucket that leaks at a constant rate (e.g., 1 failure per second).
If the bucket overflows, the circuit opens.

* **Pick this if:** You want a "forgiving" system that ignores a steady but low "background noise"
  of errors but trips immediately on a sudden burst.
* **Selection Metric:** Set the leak rate to your **expected tolerable error rate**.

#### **Continuous-Time EWMA**

A sophisticated mathematical model where newer failures carry more weight than older ones, decaying exponentially over
time.

* **Pick this if:** You need highly reactive yet smooth transitions and want to avoid the "cliff effect"
  where many errors suddenly drop out of a sliding window at once.
* **Selection Metric:** Choose a **Time Constant (Tau)** that matches your desired reaction speed.

---

### 3. Summary Checklist: Which one for me?

1. **Do you have high/varying traffic?** → Use **Time-Based Error Rate**.
2. **Is your traffic very sparse/slow?** → Use **Count-Based Sliding Window**.
3. **Do you need to trip instantly on a total outage?** → Add **Consecutive Failures**.
4. **Do you have complex, multi-layered requirements?** → Use **Composite Failure Metrics**.

> **DevOps Tip:** Always check the `StateTransition` reason in your logs. Our implementation provides
> a detailed explanation (e.g., *"Failure rate of 75.0% in the last 10s exceeded threshold of 50%"*)
> to help you diagnose issues during on-call shifts.


---
