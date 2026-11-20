import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import './styles.css'
import { installTableSort, installRequiredCheck } from './components/enhance'
installTableSort()
installRequiredCheck()
import { SessionProvider, useSession } from './session'
import { AppShell } from './components/AppShell'
import { Planned } from './components/ui'
import { Login } from './pages/Login'
import { Home } from './pages/Home'
import { Org } from './pages/Org'
import { Blueprint } from './pages/Blueprint'
import { AdminUsers } from './pages/AdminUsers'
import { AdminStores } from './pages/AdminStores'
import { AdminCatalog } from './pages/AdminCatalog'
import { AdminApprovalLines } from './pages/AdminApprovalLines'
import { Logistics } from './pages/Logistics'
import { LogisticsNew } from './pages/LogisticsNew'
import { LogisticsDetail } from './pages/LogisticsDetail'
import { LogisticsBoard } from './pages/LogisticsBoard'
import { Approvals } from './pages/Approvals'
import { Inventory } from './pages/Inventory'
import { StockCount } from './pages/StockCount'
import { Adjustments, AdjustmentNew, AdjustmentDetail } from './pages/Adjustments'
import { Sales } from './pages/Sales'
import { Expenses } from './pages/Expenses'
import { Settlement } from './pages/Settlement'
import { Statements } from './pages/Statements'
import { Tax } from './pages/Tax'
import { AdminRoyalty } from './pages/AdminRoyalty'
import { Hr } from './pages/Hr'
import { Payroll, PayrollDetail } from './pages/Payroll'
import { My } from './pages/My'
import { Reports, ReportView } from './pages/Reports'
import { Compare } from './pages/Compare'
import { Investment } from './pages/Investment'
import { Investor } from './pages/Investor'
import { StoreWizard, StoreWizardStart } from './pages/StoreWizard'
import { AdminPayrollRates, AdminReportSections, AdminAccounts } from './pages/AdminMisc'
import { AdminSalaryGrades } from './pages/AdminSalaryGrades'
import { Schedule } from './pages/Schedule'
import { Trends } from './pages/Trends'
import { LogisticsTracking } from './pages/LogisticsTracking'
import { Closing } from './pages/Closing'
import { InvestorPayouts } from './pages/InvestorPayouts'

function Guard({ children }: { children: React.ReactNode }) {
  const s = useSession()
  if (!s.ready) return <div className="boot">불러오는 중…</div>
  if (!s.user) return <Navigate to="/login" replace />
  return <>{children}</>
}

const P = (title: string, stage: number, items: string[]) => <Planned title={title} stage={stage} items={items} />

function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route element={<Guard><AppShell /></Guard>}>
        <Route index element={<Home />} />
        <Route path="org" element={<Org />} />
        <Route path="blueprint" element={<Blueprint />} />
        <Route path="admin/users" element={<AdminUsers />} />
        <Route path="admin/stores" element={<AdminStores />} />
        <Route path="admin/stores/new" element={<StoreWizardStart />} />
        <Route path="admin/stores/wizard/:id" element={<StoreWizard />} />
        <Route path="admin/payroll-rates" element={<AdminPayrollRates />} />
        <Route path="admin/salary-grades" element={<AdminSalaryGrades />} />
        <Route path="schedule" element={<Schedule />} />
        <Route path="trends" element={<Trends />} />
        <Route path="admin/report-sections" element={<AdminReportSections />} />
        <Route path="admin/accounts" element={<AdminAccounts />} />
        <Route path="admin/catalog" element={<AdminCatalog />} />
        <Route path="admin/approval-lines" element={<AdminApprovalLines />} />
        <Route path="logistics" element={<Logistics />} />
        <Route path="logistics/new" element={<LogisticsNew />} />
        <Route path="logistics/board" element={<LogisticsBoard />} />
        <Route path="logistics/tracking" element={<LogisticsTracking />} />
        <Route path="closing" element={<Closing />} />
        <Route path="investor-payouts" element={<InvestorPayouts />} />
        <Route path="logistics/:id" element={<LogisticsDetail />} />
        <Route path="approvals" element={<Approvals />} />
        <Route path="inventory" element={<Inventory />} />
        <Route path="inventory/count/:id" element={<StockCount />} />
        <Route path="adjustments" element={<Adjustments />} />
        <Route path="adjustments/new" element={<AdjustmentNew />} />
        <Route path="adjustments/:id" element={<AdjustmentDetail />} />
        <Route path="sales" element={<Sales />} />
        <Route path="expenses" element={<Expenses />} />
        <Route path="settlement" element={<Settlement />} />
        <Route path="statements" element={<Statements />} />
        <Route path="tax" element={<Tax />} />
        <Route path="admin/royalty" element={<AdminRoyalty />} />
        <Route path="hr" element={<Hr />} />
        <Route path="payroll" element={<Payroll />} />
        <Route path="payroll/:id" element={<PayrollDetail />} />
        <Route path="my" element={<My />} />
        <Route path="reports" element={<Reports />} />
        <Route path="reports/:id" element={<ReportView />} />
        <Route path="compare" element={<Compare />} />
        <Route path="investment" element={<Investment />} />
        <Route path="investor" element={<Investor />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <SessionProvider><App /></SessionProvider>
    </BrowserRouter>
  </React.StrictMode>,
)
