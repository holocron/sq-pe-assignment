import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ApiError } from '../../api/errors'
import { Table, type Column } from './Table'

interface Row {
  id: string
  name: string
}

const columns: Column<Row>[] = [
  { key: 'name', header: 'Name', cell: (row) => row.name },
]

const rows: Row[] = [
  { id: '1', name: 'Ada Lovelace' },
  { id: '2', name: 'Grace Hopper' },
]

describe('Table', () => {
  it('renders the populated state', () => {
    render(<Table columns={columns} rows={rows} rowKey={(row) => row.id} caption="People" />)
    expect(screen.getByText('Ada Lovelace')).toBeInTheDocument()
    expect(screen.getByText('Grace Hopper')).toBeInTheDocument()
  })

  it('renders the loading state without content', () => {
    render(<Table columns={columns} rows={[]} rowKey={(row) => row.id} loading skeletonRows={3} />)
    expect(screen.queryByText('No records')).not.toBeInTheDocument()
    expect(screen.getAllByRole('row')).toHaveLength(4)
  })

  it('renders the empty state', () => {
    render(
      <Table
        columns={columns}
        rows={[]}
        rowKey={(row) => row.id}
        emptyTitle="No transactions"
      />,
    )
    expect(screen.getByText('No transactions')).toBeInTheDocument()
  })

  it('renders the error state with the problem detail', () => {
    render(
      <Table
        columns={columns}
        rows={[]}
        rowKey={(row) => row.id}
        error={new ApiError({ status: 500, title: 'Server error', detail: 'Boom' })}
      />,
    )
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.getByText('Boom')).toBeInTheDocument()
  })
})
