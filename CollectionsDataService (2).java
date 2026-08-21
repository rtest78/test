<!doctype html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Collections Daily Dashboard</title>
  <link rel="stylesheet" th:href="@{/css/app.css}">
</head>
<body>
<header class="topbar">
  <div class="logo">CD</div>
  <div>
    <strong>Collections Daily Dashboard</strong>
    <small>Account analysis and monitoring</small>
  </div>
  <span class="source" th:text="${loadedDateCount + ' business dates loaded'}"></span>
</header>

<main class="shell">
  <section class="hero">
    <p class="eyebrow">Daily collections analysis</p>
    <h1>Collections Daily Dashboard</h1>
    <p>Review application changes, risk-level movement, channel movement, and accounts that may have been paid off.</p>
  </section>

  <div class="flash success" th:if="${message}" th:text="${message}"></div>
  <div class="flash error" th:if="${error}" th:text="${error}"></div>

  <section class="toolbar">
    <form method="get">
      <label>Business date
        <select name="date" onchange="this.form.submit()">
          <option th:each="d:${availableDates}"
                  th:value="${d}"
                  th:text="${d}"
                  th:selected="${d==selectedDate}"></option>
        </select>
      </label>
    </form>

    <div>
      Compared with
      <strong th:text="${comparisonSummary.previousDate ?: 'No earlier date'}"></strong>
    </div>

    <form th:action="@{/reload}" method="post">
      <button class="secondary">Reload files</button>
    </form>
  </section>

  <section class="metrics">
    <article>
      <span>Applications processed</span>
      <b th:text="${comparisonSummary.currentAccounts}">0</b>
    </article>
    <article>
      <span>Applications changed</span>
      <b th:text="${comparisonSummary.changedAccounts}">0</b>
    </article>
    <article class="risk">
      <span>Risk level changed</span>
      <b th:text="${comparisonSummary.riskLevelChanged}">0</b>
    </article>
    <article class="channel">
      <span>Channel changed</span>
      <b th:text="${comparisonSummary.channelChanged}">0</b>
    </article>
    <article>
      <span>New applications</span>
      <b th:text="${comparisonSummary.newAccounts}">0</b>
    </article>
    <article>
      <span>Paid-off candidates</span>
      <b th:text="${comparisonSummary.missingAccounts}">0</b>
    </article>
  </section>

  <!-- NEW: selected business date channel counts -->
  <section class="panel">
    <div class="panel-title">
      <div>
        <p class="eyebrow">Channel distribution</p>
        <h2 th:text="${'Accounts sent to each channel on ' + selectedDate}">Accounts sent to each channel</h2>
        <p>Account totals are grouped by the channel returned for the selected business date.</p>
      </div>
    </div>

    <div class="channel-bars">
      <div class="rank-row" th:each="channel:${channelCounts}">
        <strong th:text="${channel.key}">Channel</strong>
        <b th:text="${channel.value}">0</b>
      </div>

      <p class="empty" th:if="${channelCounts == null or channelCounts.isEmpty()}">
        No channel information was found for this date.
      </p>
    </div>
  </section>

  <section class="panel payoff-panel">
    <div class="panel-title">
      <div>
        <p class="eyebrow">Paid-off verification</p>
        <h2>Paid-off candidates by previous channel</h2>
        <p>Accounts present on the prior date but absent on the selected date. Verify before confirming payoff.</p>
      </div>
      <a class="button" th:href="@{/paid-off(date=${selectedDate})}">Open verification page</a>
    </div>

    <div class="channel-bars">
      <div class="bar-row" th:each="c:${comparisonSummary.paidOffByChannel}">
        <strong th:text="${c.channel}"></strong>
        <div class="bar-track">
          <span th:style="${'width:' + (comparisonSummary.missingAccounts == 0 ? 0 : (c.count * 100 / comparisonSummary.missingAccounts)) + '%'}"></span>
        </div>
        <b th:text="${c.count}"></b>
      </div>

      <p class="empty" th:if="${comparisonSummary.paidOffByChannel.empty}">
        No paid-off candidates for this comparison.
      </p>
    </div>
  </section>

  <section class="movement">
    <article class="panel">
      <div class="panel-title">
        <div>
          <p class="eyebrow">Risk movement</p>
          <h2>Risk-level transitions</h2>
        </div>
        <span class="pill risk-pill" th:text="${comparisonSummary.riskLevelChanged + ' apps'}"></span>
      </div>

      <div class="transition" th:each="t:${comparisonSummary.riskTransitions}">
        <span th:text="${t.fromValue}"></span>
        <i>→</i>
        <strong th:text="${t.toValue}"></strong>
        <b th:text="${t.count}"></b>
      </div>

      <p class="empty" th:if="${comparisonSummary.riskTransitions.empty}">No risk-level changes.</p>
    </article>

    <article class="panel">
      <div class="panel-title">
        <div>
          <p class="eyebrow">Routing movement</p>
          <h2>Channel transitions</h2>
        </div>
        <span class="pill channel-pill" th:text="${comparisonSummary.channelChanged + ' apps'}"></span>
      </div>

      <div class="transition" th:each="t:${comparisonSummary.channelTransitions}">
        <span th:text="${t.fromValue}"></span>
        <i>→</i>
        <strong th:text="${t.toValue}"></strong>
        <b th:text="${t.count}"></b>
      </div>

      <p class="empty" th:if="${comparisonSummary.channelTransitions.empty}">No channel changes.</p>
    </article>
  </section>

  <!-- NEW: every business date and every channel count -->
  <section class="panel">
    <div class="panel-title">
      <div>
        <p class="eyebrow">Historical channel reporting</p>
        <h2>Channel counts by business date</h2>
        <p>Each row shows the account count sent to a channel on that business date.</p>
      </div>
    </div>

    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Business Date</th>
            <th>Channel</th>
            <th>Account Count</th>
          </tr>
        </thead>

        <tbody th:each="day:${channelCountsByDate}">
          <tr th:each="channel:${day.value}">
            <td>
              <a th:href="@{/(date=${day.key})}" th:text="${day.key}"></a>
            </td>
            <td th:text="${channel.key}">Channel</td>
            <td><strong th:text="${channel.value}">0</strong></td>
          </tr>
        </tbody>
      </table>
    </div>

    <p class="empty" th:if="${channelCountsByDate == null or channelCountsByDate.isEmpty()}">
      No historical channel information is available.
    </p>
  </section>

  <section class="panel">
    <div class="panel-title">
      <div>
        <p class="eyebrow">Loaded extracts</p>
        <h2>Daily file quality</h2>
      </div>
      <a class="button" th:href="@{/accounts(date=${selectedDate})}">View application detail</a>
    </div>

    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Date</th>
            <th>Members</th>
            <th>Applications</th>
            <th>Changed</th>
            <th>Risk changed</th>
            <th>Channel changed</th>
            <th>New</th>
            <th>Paid-off candidates</th>
            <th>Rejected</th>
            <th>Source file</th>
          </tr>
        </thead>

        <tbody>
          <tr th:each="s:${summaries}">
            <td><a th:href="@{/(date=${s.businessDate})}" th:text="${s.businessDate}"></a></td>
            <td th:text="${s.memberCount}"></td>
            <td th:text="${s.accountCount}"></td>
            <td th:text="${s.changedAccounts}"></td>
            <td th:text="${s.riskLevelChanged}"></td>
            <td th:text="${s.channelChanged}"></td>
            <td th:text="${s.newAccounts}"></td>
            <td><a th:href="@{/paid-off(date=${s.businessDate})}" th:text="${s.missingAccounts}"></a></td>
            <td th:text="${s.rejectedLines}"></td>
            <td th:text="${s.sourceFile}"></td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <footer>Data directory: <code th:text="${dataDirectory}"></code></footer>
</main>
</body>
</html>
