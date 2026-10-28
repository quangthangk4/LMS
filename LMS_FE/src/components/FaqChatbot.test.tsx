import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import FaqChatbot from '../../components/FaqChatbot';
import { chatbotService } from '../../api/chatbotService';
let mockLanguage: 'vi'|'en'='vi';let mockUserType: string|null=null;let mockSessionVersion=0;let mockUploads: unknown[]=[];
jest.mock('../../contexts/AuthContext',()=>({useAuth:()=>({userType:mockUserType,sessionVersion:mockSessionVersion})}));
jest.mock('../../contexts/LanguageContext',()=>({useLanguage:()=>({language:mockLanguage})}));
jest.mock('../../contexts/UploadContext',()=>({useUpload:()=>({uploads:mockUploads})}));
jest.mock('../../api/chatbotService',()=>({chatbotService:{suggestions:jest.fn(),ask:jest.fn(),clear:jest.fn().mockResolvedValue(undefined)}}));
const ask=chatbotService.ask as jest.Mock;const suggestions=chatbotService.suggestions as jest.Mock;
const view=()=>render(<MemoryRouter><FaqChatbot /></MemoryRouter>);
async function open() {await userEvent.click(screen.getByRole('button',{name:'Mở trợ lý hướng dẫn'}));await screen.findByRole('combobox');}
function send(text: string) {fireEvent.change(screen.getByRole('textbox'),{target:{value:text}});fireEvent.keyDown(screen.getByRole('textbox'),{key:'Enter'});}
beforeEach(()=>{jest.clearAllMocks();mockLanguage='vi';mockUserType=null;mockSessionVersion=0;mockUploads=[];
  suggestions.mockResolvedValue({data:[{id:'borrow',question:'Cách mượn sách?'}]});
  ask.mockResolvedValue({data:{status:'ANSWERED',format:'markdown',answer:'Bạn hãy **đăng nhập**.\n\n[Xem hướng dẫn](/publicpage/guide#borrow)'}});
});
test('opens without an AI request and renders the actual model Markdown without generated blocks',async()=>{
  view();await open();expect(ask).not.toHaveBeenCalled();expect(screen.getByRole('textbox')).toHaveFocus();
  send('Cách mượn sách?');expect(await screen.findByText('đăng nhập')).toBeInTheDocument();
  expect(screen.getByText('đăng nhập').tagName).toBe('STRONG');expect(screen.queryByRole('article')).not.toBeInTheDocument();
  expect(screen.getByRole('link',{name:'Xem hướng dẫn'})).toHaveAttribute('href','/publicpage/guide#borrow');
  expect(ask).toHaveBeenCalledWith('Cách mượn sách?','vi',expect.any(AbortSignal),false,expect.any(String));
});
test('shows Thinking while awaiting Gemini and prevents duplicate submissions',async()=>{
  ask.mockReturnValue(new Promise(()=>{}));view();await open();send('Chào');
  expect(screen.getByRole('status')).toHaveTextContent('Đang suy nghĩ…');send('Câu tiếp');expect(ask).toHaveBeenCalledTimes(1);
});
test('closing hides the panel but lets the answer complete so browser and Redis conversation stay aligned',async()=>{
  let finish!: (result: any)=>void;ask.mockImplementation(()=>new Promise(resolve=>{finish=resolve;}));view();await open();send('Chào');
  const signal=ask.mock.calls[0][2] as AbortSignal;fireEvent.click(screen.getByRole('button',{name:'Đóng trợ lý'}));expect(signal.aborted).toBe(false);
  await act(async()=>finish({data:{status:'ANSWERED',format:'markdown',answer:'Chào bạn, mình có thể giúp gì?'}}));
  await open();expect(screen.getByText('Chào bạn, mình có thể giúp gì?')).toBeInTheDocument();
});
test('clear invalidates the old conversation and ignores its late response',async()=>{
  let finish!: (result:any)=>void;ask.mockImplementation(()=>new Promise(resolve=>{finish=resolve;}));view();await open();send('Java');
  const id=ask.mock.calls[0][4];const signal=ask.mock.calls[0][2] as AbortSignal;
  fireEvent.click(screen.getByRole('button',{name:'Xóa hội thoại'}));expect(signal.aborted).toBe(true);expect(chatbotService.clear).toHaveBeenCalledWith(id,false);
  await act(async()=>finish({data:{answer:'Stale response'}}));expect(screen.queryByText('Stale response')).not.toBeInTheDocument();
  ask.mockResolvedValue({data:{answer:'New answer',format:'markdown'}});send('Câu mới');await screen.findByText('New answer');
  expect(ask.mock.calls[1][4]).not.toBe(id);
});
test('follow-up sends only the new question and the same conversation ID',async()=>{
  view();await open();send('Java');await screen.findByText('đăng nhập');const id=ask.mock.calls[0][4];
  send('Còn CS2?');await waitFor(()=>expect(ask).toHaveBeenCalledTimes(2));
  expect(ask.mock.calls[1]).toEqual(['Còn CS2?','vi',expect.any(AbortSignal),false,id]);
});
test('private chat uses the authenticated endpoint and account changes reset the visible conversation',async()=>{
  mockUserType='student';const rendered=view();await open();send('Sách cần trả?');await screen.findByText('đăng nhập');
  expect(ask.mock.calls[0][3]).toBe(true);mockSessionVersion++;
  rendered.rerender(<MemoryRouter><FaqChatbot /></MemoryRouter>);
  await open();expect(screen.queryByText('đăng nhập')).not.toBeInTheDocument();
});
test('expired authentication clears sensitive messages and displays a sign-in error',async()=>{
  mockUserType='student';view();await open();send('Sách của tôi');await screen.findByText('đăng nhập');
  ask.mockRejectedValue({status:401});send('Câu tiếp');expect(await screen.findByRole('alert')).toHaveTextContent('đăng nhập lại');
  expect(screen.queryByText('đăng nhập')).not.toBeInTheDocument();
});
test('shows session reset and output truncation as separate transport notices',async()=>{
  view();await open();send('Chào');await screen.findByText('đăng nhập');
  ask.mockResolvedValue({data:{answer:'Phần trả lời còn tiếp…',format:'markdown',contextReset:true,reasonCode:'OUTPUT_TRUNCATED'}});send('Tiếp');
  await screen.findByText('Phần trả lời còn tiếp…');expect(screen.getByText(/Ngữ cảnh trước đó/)).toBeInTheDocument();expect(screen.getByText(/Câu trả lời bị ngắt/)).toBeInTheDocument();
});
test('upload panel still takes precedence over chat',()=>{mockUploads=[{}];view();expect(screen.queryByRole('button',{name:'Mở trợ lý hướng dẫn'})).not.toBeInTheDocument();});
